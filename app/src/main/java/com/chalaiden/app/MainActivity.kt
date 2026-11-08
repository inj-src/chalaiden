package com.chalaiden.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import kotlinx.coroutines.withContext
import com.chalaiden.app.ui.AmountScreen
import com.chalaiden.app.ui.HomeScreen
import com.chalaiden.app.ui.GuideCover
import com.chalaiden.app.ui.NumberScreen
import com.chalaiden.app.ui.ResultScreen
import com.chalaiden.app.ui.RunCover
import com.chalaiden.app.ui.PinEntry
import com.chalaiden.app.ui.RunOverlay
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ui.ScanScreen
import com.chalaiden.app.ui.SetupScreen
import com.chalaiden.app.ui.MethodGuideScreen
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ussd.Backend
import com.chalaiden.app.ussd.Dialer
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Method
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.GuideSession
import com.chalaiden.app.ussd.Prefs
import com.chalaiden.app.ussd.Privilege
import com.chalaiden.app.ussd.ShizukuAccess
import com.chalaiden.app.ussd.UssdDriver
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdFlow
import com.chalaiden.app.ussd.UssdSession

private sealed interface Screen {
    data object Home : Screen
    data class Scan(val kind: FlowKind) : Screen
    /** [groups]: how the scanned card spaced the digits, so the rail can match it; null for the usual 3-4-4. */
    data class Number(val kind: FlowKind, val number: String, val crop: Bitmap?, val groups: List<Int>? = null) : Screen
    data class Amount(val kind: FlowKind, val number: String, val groups: List<Int>? = null) : Screen
    data object Running : Screen
    data class Result(val flow: UssdFlow, val outcome: Outcome) : Screen
    data class Setup(val then: UssdFlow?) : Screen
    /** Step-by-step set-up for one way of answering bKash, opened from Setup. */
    data class MethodGuide(val method: Method) : Screen
}

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val prefs by lazy { Prefs(this) }

    /** Bumped when setup changes outside Compose (root or Shizuku fixes), so screens re-check readiness. */
    private var setupTick by mutableIntStateOf(0)

    /** Cached result of the last root check; readiness reads this instead of spawning `su` in composition. */
    @Volatile private var rootOk = false

    /** Same for Shizuku, which is only tried without root. */
    @Volatile private var shizukuOk = false
    private var stopWatchingShizuku: (() -> Unit)? = null

    /**
     * Debug builds only: pretend root (or root and Shizuku) isn't there, to try the other modes on a rooted
     * phone. `adb shell am start -n com.chalaiden.app/.MainActivity --es mode shizuku|guided|auto`.
     */
    @Volatile private var debugMode: String? = null

    /**
     * Debug builds only: pretend Shizuku is in this state, to see each path of its guide without
     * uninstalling it. `--es shizuku_status NotInstalled|NotRunning|Outdated|NeedsPermission|Denied`.
     */
    private var debugShizuku by mutableStateOf<ShizukuAccess.Status?>(null)

    private fun shizukuStatus(): ShizukuAccess.Status = debugShizuku ?: ShizukuAccess.status(this)

    /** Guided mode: waiting for the keyboard picker to close before dialling (see [startGuided]). */
    private var pendingGuide: UssdFlow? = null

    /** Ends the guide once Chalaiden is back in front for good (bKash's dialog closed). */
    private var guideEnd: Job? = null

    /** Which way the last navigation went, so screens slide in from the right side. */
    private var forward by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        RunCover.attach(this)
        GuideSession.bind(this)
        GuideCover.attach(this)
        // "Hold to pay" is pressed on the cover, but only a foreground activity can dial *247#.
        UssdSession.starter = { flow -> drive(flow) }
        // Shizuku hands over its binder a moment after launch, and loses it when it stops: re-check then.
        stopWatchingShizuku = ShizukuAccess.watch { checkSetup() }
        handle(intent)
        // First launch: phone access was never given, so open on the setup steps.
        if (savedInstanceState == null && !Dialer.hasPermissions(this) && stack.toList() == listOf(Screen.Home)) {
            stack += Screen.Setup(then = null)
        }
        setContent { ChalaidenTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        GuideSession.setAppVisible(true)
        GuideSession.consumeFinished()
        checkSetup()
    }

    override fun onPause() {
        super.onPause()
        GuideSession.setAppVisible(false)
    }

    /**
     * bKash's USSD dialog is a window of the phone app, not an activity: Chalaiden stays resumed under it
     * and only loses focus. So the guide follows focus, and floats over that dialog.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val front = hasFocus && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        GuideSession.setAppVisible(front)
        guideEnd?.cancel()
        if (!front) return
        // The keyboard picker closed: now open bKash's menu.
        pendingGuide?.let { pendingGuide = null; return dialGuided(it) }
        // bKash's dialog is gone and Chalaiden stayed in front: the guide is over. The wait rides out
        // the moment between two bKash prompts when no dialog is up.
        if (GuideSession.state.value != null) {
            val back = System.currentTimeMillis()
            guideEnd = lifecycleScope.launch {
                delay(2_500)
                if (hasWindowFocus()) GuideSession.end(GuideSession.End.Gone, at = back)
            }
        }
    }

    override fun onDestroy() {
        stopWatchingShizuku?.invoke()
        stopWatchingShizuku = null
        super.onDestroy()
    }

    /**
     * Grants what root (or, without root, Shizuku) can grant: permissions and the overlay. Then refreshes
     * readiness. Never enables Accessibility.
     */
    private fun checkSetup(then: () -> Unit = {}) {
        lifecycleScope.launch {
            val (root, shizuku) = withContext(Dispatchers.IO) {
                val mode = debugMode
                val root = mode == null && Privilege.ensureSetup(this@MainActivity, Backend.Root)
                // Checked every time, even with root: Setup offers each way that works, and Shizuku stops at
                // each reboot and may be started again at any point.
                val shizuku = mode != "guided" && ShizukuAccess.isReady(this@MainActivity) &&
                    Privilege.ensureSetup(this@MainActivity, Backend.Shizuku)
                root to shizuku
            }
            rootOk = root
            shizukuOk = shizuku
            setupTick++
            then()
        }
    }

    /**
     * What the automatic driver runs on: the way the user chose in Setup, if it works; with no choice yet,
     * root first, then Shizuku. Null means guided mode or setup.
     */
    private fun backend(): Backend? = when (prefs.method) {
        Method.Root -> Backend.Root.takeIf { rootOk }
        Method.Shizuku -> Backend.Shizuku.takeIf { shizukuOk }
        Method.Guided -> null
        null -> when {
            rootOk -> Backend.Root
            shizukuOk -> Backend.Shizuku
            else -> null
        }
    }

    /** The way bKash gets answered right now, or null if none works yet. */
    private fun inUse(): Method? = when (backend()) {
        Backend.Root -> Method.Root
        Backend.Shizuku -> Method.Shizuku
        null -> Method.Guided.takeIf { prefs.method == Method.Guided }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.hasExtra("mode") == true) {
            debugMode = intent.getStringExtra("mode")?.takeIf { it == "shizuku" || it == "guided" }
            checkSetup()
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("shizuku_status") == true) {
            debugShizuku = ShizukuAccess.Status.entries.firstOrNull { it.name == intent.getStringExtra("shizuku_status") }
        }
        // Debug builds only: show the guided card for a made-up payment, without dialling.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("guide_demo", false) == true) {
            val demo = Flows.payment("01712345678", "250", null).copy(groups = listOf(5, 3, 3))
            stack.clear()
            stack += Screen.Home
            GuideSession.start(demo)
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("ussd_probe") == true) {
            com.chalaiden.app.ussd.UssdProbe.send(this, intent.getStringExtra("ussd_probe").orEmpty(), simId())
        }
        if (intent?.action == ACTION_SCAN && !UssdSession.isActive) {
            stack.clear()
            stack += listOf(Screen.Home, Screen.Scan(FlowKind.Payment))
        }
    }

    private fun push(s: Screen) { forward = true; stack += s }
    private fun pop() { forward = false; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    private fun replaceTop(s: Screen) { forward = true; stack[stack.lastIndex] = s }
    private fun home() { forward = false; stack.clear(); stack += Screen.Home }

    private fun phoneReady() = Dialer.hasPermissions(this) && simId() != null

    /** Nothing left to set up: automatic answering works, or the user chose guided mode. */
    private fun ready() = phoneReady() && inUse() != null

    /** For Home's menu: how bKash gets answered now. */
    private fun setupStatus(): String = when {
        !phoneReady() -> "Not finished"
        else -> when (inUse()) {
            Method.Root -> "Automatic, with root"
            Method.Shizuku -> "Automatic, with Shizuku"
            Method.Guided -> "Guided mode"
            null -> "Not finished"
        }
    }

    /** A killed session left the keyboards off, and neither root nor Shizuku is here to turn them back on. */
    private fun keyboardOff(): Boolean = prefs.imeToRestore != null && !UssdSession.isActive &&
        runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.isEmpty() }
            .getOrDefault(false)

    private fun simId(): Int? {
        val sims = Dialer.sims(this)
        return prefs.simSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } }
            ?: sims.singleOrNull()?.subscriptionId
    }

    private fun start(flow: UssdFlow) {
        if (phoneReady() && backend() != null) return run(flow)
        checkSetup {
            when {
                phoneReady() && backend() != null -> run(flow)
                phoneReady() && prefs.method == Method.Guided -> startGuided(flow)
                else -> push(Screen.Setup(then = flow))
            }
        }
    }

    /**
     * Guided mode: straight to bKash's menu, with the card floating over it. With the Chalaiden keyboard
     * switched on, Android's keyboard picker comes first (only the user may change keyboards); the dial
     * waits for it to close, see [onWindowFocusChanged].
     */
    private fun startGuided(flow: UssdFlow) {
        if (!android.provider.Settings.canDrawOverlays(this) && !ChalaidenKeyboard.isEnabled(this)) {
            return push(Screen.Setup(then = flow))
        }
        home()
        if (ChalaidenKeyboard.isEnabled(this) && !ChalaidenKeyboard.isCurrent(this) && ChalaidenKeyboard.pick(this)) {
            pendingGuide = flow
            // If the picker never took focus (some phones refuse it), dial anyway.
            lifecycleScope.launch {
                delay(800)
                if (pendingGuide == flow && hasWindowFocus()) { pendingGuide = null; dialGuided(flow) }
            }
        } else {
            dialGuided(flow)
        }
    }

    private fun dialGuided(flow: UssdFlow) {
        GuideSession.start(flow) // before dialling, so the card is ready as bKash's dialog comes up
        runCatching { startActivity(Dialer.callIntent(this, simId())) }.onFailure { GuideSession.end(GuideSession.End.Closed) }
    }

    /** Shows the PIN screen. Nothing is dialled until "Hold to pay" is pressed (see [drive]). */
    private fun run(flow: UssdFlow) {
        PinEntry.reset()
        UssdSession.prepare(flow)
        push(Screen.Running)
    }

    private fun drive(flow: UssdFlow) {
        // Driver sweeps leftover dialogs and drives the menu; the activity dials, because the
        // USSD dialog only launches from a foreground app (OxygenOS blocks it otherwise).
        val sim = simId()
        // Root unless only Shizuku works; a backend that has gone since ends the session as not started.
        UssdDriver.start(this, flow, backend() ?: Backend.Root) {
            withContext(Dispatchers.Main) {
                runCatching {
                    startActivity(Dialer.callIntent(this@MainActivity, sim))
                }.onFailure {
                    UssdSession.clear()
                    replaceTop(Screen.Result(flow, Outcome.Failed("Couldn't open the bKash menu: ${it.message}", pinSent = false)))
                }
            }
        }
    }

    @Composable
    private fun App() {
        val session by UssdSession.current.collectAsState()
        LaunchedEffect(session) {
            // Left the PIN screen without paying: back to where the user came from.
            val s = session ?: return@LaunchedEffect run { if (stack.lastOrNull() == Screen.Running) pop() }
            val state = s.state
            if (state is RunState.Finished && stack.lastOrNull() == Screen.Running) {
                PinEntry.reset() // digits typed before a failure while walking the menu
                replaceTop(Screen.Result(s.flow, state.outcome))
                UssdSession.clear()
            }
        }
        @Suppress("UNUSED_VARIABLE") val tick = setupTick // re-read readiness after root or Shizuku fixes
        BackHandler(enabled = stack.size > 1 && stack.last() != Screen.Running) { pop() }
        // On the PIN screen, back leaves it (closing a warm menu); once paying, back does nothing.
        BackHandler(enabled = stack.lastOrNull() == Screen.Running && session?.confirmed == false) {
            PinEntry.reset()
            UssdSession.abandon()
        }

        // Screens keep their state (typed amount, number) while they are in the stack, so coming back
        // from the PIN screen finds them as they were; a screen's state is dropped once it is popped.
        val saved = rememberSaveableStateHolder()
        val keys = stack.map { it.toString() }
        val known = remember { mutableSetOf<String>() }
        LaunchedEffect(keys) {
            (known - keys.toSet()).forEach(saved::removeState)
            known.retainAll(keys.toSet())
            known += keys
        }
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { screenTransition(initialState, targetState, forward) },
            modifier = Modifier.fillMaxSize().background(tokens.bg),
            label = "screens",
        ) { screen -> saved.SaveableStateProvider(screen.toString()) { ScreenContent(screen) } }
    }

    @Composable
    private fun ScreenContent(screen: Screen) {
        when (screen) {
            Screen.Home -> HomeScreen(
                sims = Dialer.sims(this),
                simId = setupTick.let { simId() }, // re-read after a SIM change
                onSelectSim = { prefs.simSubscriptionId = it; setupTick++ },
                setupDone = ready(),
                keyboardOff = setupTick.let { keyboardOff() },
                onFixKeyboard = { runCatching { startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)) } },
                onPay = { push(Screen.Scan(FlowKind.Payment)) },
                onSend = { push(Screen.Number(FlowKind.SendMoney, "", null)) },
                onCashOut = { push(Screen.Number(FlowKind.CashOut, "", null)) },
                onBalance = { start(Flows.balance()) },
                onSetup = { push(Screen.Setup(then = null)) },
                setupStatus = setupTick.let { setupStatus() },
            )
            is Screen.Scan -> ScanScreen(
                onRead = { read ->
                    pop()
                    val next = Screen.Number(screen.kind, read.number.orEmpty(), read.crop, read.groups)
                    if ((stack.last() as? Screen.Number)?.kind == screen.kind) replaceTop(next) else push(next)
                },
                onTypeInstead = { replaceTop(Screen.Number(screen.kind, "", null)) },
                onBack = ::pop,
            )
            is Screen.Number -> NumberScreen(
                kind = screen.kind,
                initial = screen.number,
                crop = screen.crop,
                groups = screen.groups,
                onScan = { push(Screen.Scan(screen.kind)) },
                onContinue = { push(Screen.Amount(screen.kind, it, screen.groups)) },
                onBack = ::pop,
            )
            is Screen.Amount -> AmountScreen(
                kind = screen.kind,
                number = screen.number,
                groups = screen.groups,
                onPay = { amount, reference ->
                    start(
                        when (screen.kind) {
                            FlowKind.Payment -> Flows.payment(screen.number, amount, reference)
                            FlowKind.SendMoney -> Flows.sendMoney(screen.number, amount, reference)
                            FlowKind.CashOut -> Flows.cashOut(screen.number, amount)
                            FlowKind.Balance -> Flows.balance()
                        }.copy(groups = screen.groups),
                    )
                },
                onBack = ::pop,
            )
            // The same screen the cover shows over the phone's dialogs; seen here if the cover can't be drawn.
            Screen.Running -> RunOverlay()
            is Screen.Result -> ResultScreen(
                flow = screen.flow,
                outcome = screen.outcome,
                onRetry = { pop(); start(screen.flow) },
                onCheckBalance = { home(); start(Flows.balance()) },
                onDone = ::home,
            )
            is Screen.Setup -> SetupScreen(
                prefs = prefs,
                rootOk = rootOk,
                shizukuOk = shizukuOk,
                shizuku = setupTick.let { shizukuStatus() },
                inUse = setupTick.let { inUse() },
                onChoose = { prefs.method = it; checkSetup() },
                onGuide = { push(Screen.MethodGuide(it)) },
                onRecheck = { checkSetup() },
                onDone = { pop(); screen.then?.let(::start) },
                onBack = ::pop,
            )
            is Screen.MethodGuide -> MethodGuideScreen(
                method = screen.method,
                shizuku = setupTick.let { shizukuStatus() },
                shizukuOk = shizukuOk,
                rootOk = rootOk,
                inUse = setupTick.let { inUse() },
                onRecheck = { checkSetup() },
                onUse = { prefs.method = it; checkSetup(); pop() },
                onBack = ::pop,
            )
        }
    }

    /**
     * Shared-axis motion: the new screen slides a short way in from the side it comes from while
     * fading up; the old one drifts the other way and fades out. Results arrive with a gentle scale.
     */
    private fun AnimatedContentTransitionScope<Screen>.screenTransition(from: Screen, to: Screen, forward: Boolean): ContentTransform {
        val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        if (to is Screen.Result) {
            return (fadeIn(tween(320, delayMillis = 60)) + scaleIn(tween(420, easing = ease), initialScale = 0.94f))
                .togetherWith(fadeOut(tween(160)))
        }
        val dir = if (forward) 1 else -1
        return (slideInHorizontally(tween(380, easing = ease)) { dir * it / 7 } + fadeIn(tween(260, delayMillis = 70)))
            .togetherWith(slideOutHorizontally(tween(380, easing = ease)) { -dir * it / 12 } + fadeOut(tween(140)))
            .apply { targetContentZIndex = if (forward) 1f else 0f }
    }

    companion object {
        const val ACTION_SCAN = "com.chalaiden.app.SCAN"
    }
}

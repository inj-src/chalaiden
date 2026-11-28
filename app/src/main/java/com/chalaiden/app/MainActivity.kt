package com.chalaiden.app

import com.chalaiden.app.ui.components.PhoneGroups
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import android.widget.Toast
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import com.chalaiden.app.ui.AmountScreen
import com.chalaiden.app.ui.HomeScreen
import com.chalaiden.app.ui.GuideCover
import com.chalaiden.app.ui.NumberScreen
import com.chalaiden.app.ui.ResultScreen
import com.chalaiden.app.ui.RunCover
import com.chalaiden.app.ui.PinEntry
import com.chalaiden.app.ui.AmountEntry
import com.chalaiden.app.ui.StepReplay
import com.chalaiden.app.ui.leavePin
import androidx.compose.runtime.saveable.rememberSaveable
import com.chalaiden.app.ui.RunOverlay
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ui.ScanScreen
import com.chalaiden.app.ui.SetupScreen
import com.chalaiden.app.ui.MethodGuideScreen
import com.chalaiden.app.ui.AboutScreen
import com.chalaiden.app.ui.UpdateSheet
import com.chalaiden.app.ui.OneTapOfferSheet
import com.chalaiden.app.ui.ShareSheet
import com.chalaiden.app.share.ShareApp
import com.chalaiden.app.ui.WrongPinSheet
import com.chalaiden.app.ui.UpdatesScreen
import com.chalaiden.app.update.Updates
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.vendor.PhoneQuirks
import com.chalaiden.app.ussd.Backend
import com.chalaiden.app.ussd.ChalaidenAccessibility
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
import com.chalaiden.app.ui.OneTapScreen
import com.chalaiden.app.onetap.askFingerprint
import com.chalaiden.app.onetap.Touch
import com.chalaiden.app.onetap.OneTap

private sealed interface Screen {
    data object Home : Screen
    data class Scan(val kind: FlowKind) : Screen
    /** [groups]: how the scanned card spaced the digits, so the rail can match it; null for the usual 3-4-4. */
    data class Number(val kind: FlowKind, val number: String, val crop: Bitmap?, val groups: List<Int>? = null) : Screen
    data class Amount(val kind: FlowKind, val number: String, val groups: List<Int>? = null, val reference: String = "") : Screen
    data object Running : Screen
    data class Result(val flow: UssdFlow, val outcome: Outcome, val wave: Boolean = false) : Screen
    /** [fromStart]: opened from the menu, so the steps run again from the first rather than jumping to what's missing. */
    data class Setup(val then: UssdFlow?, val fromStart: Boolean = false) : Screen
    /** Step-by-step set-up for one way of answering bKash, opened from Setup. */
    data class MethodGuide(val method: Method) : Screen
    data object About : Screen
    data object Updates : Screen
    /** [change]: open straight on typing a new PIN (after bKash refused the saved one). */
    data class OneTap(val change: Boolean = false) : Screen
}

/** Away this long, and coming back counts as opening Chalaiden again (for One Tap's offer). */
private const val REOPEN_AFTER_MS = 30 * 60 * 1000L

// A FragmentActivity because One Tap's fingerprint sheet (androidx BiometricPrompt) is hosted by one.
class MainActivity : FragmentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val prefs by lazy { Prefs(this) }

    /** Bumped when setup changes outside Compose (root or Shizuku fixes), so screens re-check readiness. */
    private var setupTick by mutableIntStateOf(0)

    /** The screen as it last looked while paying, recorded each frame; what the success wave carries away. */
    private var screenLayer: GraphicsLayer? = null

    /** That picture, taken as a payment went through, until the wave has finished with it. */
    private var waveFrom by mutableStateOf<ImageBitmap?>(null)

    /** Cached result of the last root check; readiness reads this instead of spawning `su` in composition. */
    @Volatile private var rootOk = false

    /** Same for Shizuku, which is only tried without root. */
    @Volatile private var shizukuOk = false

    /** Chalaiden's accessibility service is switched on and connected (the Accessibility method). */
    @Volatile private var a11yOk = false
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

    /** Debug builds only: Home's made-up data and stand-in text size and width (see [DebugDemo.home]). */
    private var debugHome by mutableStateOf<DebugDemo.HomeData?>(null)
    private var debugFontScale by mutableStateOf<Float?>(null)
    private var debugWidth by mutableStateOf<Int?>(null)

    private fun shizukuStatus(): ShizukuAccess.Status = debugShizuku ?: ShizukuAccess.status(this)

    /** Guided mode: waiting for the keyboard picker to close before dialling (see [startGuided]). */
    private var pendingGuide: UssdFlow? = null

    /** Ends the guide once Chalaiden is back in front for good (bKash's dialog closed). */
    private var guideEnd: Job? = null

    /** Which way the last navigation went, so screens slide in from the right side. */
    private var forward by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        RunCover.attach(this)
        StepReplay.attach()
        OneTap.init(this)
        if (savedInstanceState == null) prefs.opens += 1 // and in onStart, when it comes back after a while
        GuideSession.bind(this)
        GuideCover.attach(this)
        // "Hold to pay" is pressed on the cover, but only a foreground activity can dial *247#.
        UssdSession.starter = { flow -> drive(flow) }
        // Shizuku hands over its binder a moment after launch, and loses it when it stops: re-check then.
        stopWatchingShizuku = ShizukuAccess.watch { checkSetup() }
        // The accessibility service connects a moment after it is switched on, and may be switched off any time.
        lifecycleScope.launch { ChalaidenAccessibility.running.drop(1).collect { checkSetup() } }
        handle(intent)
        // First launch: phone access was never given, so open on the setup steps.
        if (savedInstanceState == null && !Dialer.hasPermissions(this) && stack.toList() == listOf(Screen.Home)) {
            stack += Screen.Setup(then = null)
        }
        setContent { ChalaidenTheme { App() } }
    }

    /** When Chalaiden last went out of sight, to tell coming back to it from a trip to Settings. */
    private var stoppedAt = 0L

    override fun onStart() {
        super.onStart()
        // Back on Home keeps the activity on Android 12+, so reopening it from the launcher only starts it.
        // A long absence counts as another open; a quick trip to Settings or the dialer doesn't.
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > REOPEN_AFTER_MS) prefs.opens += 1
        stoppedAt = 0L
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        GuideSession.setAppVisible(true)
        checkSetup()
        // Asks GitHub at most every few hours; offline it fails quietly.
        lifecycleScope.launch { Updates.check(this@MainActivity) }
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
        // the moment between two bKash prompts when no dialog is up, which on some phones lasts until
        // bKash answers (see PhoneQuirks.closesDialogOnSend).
        if (GuideSession.state.value != null) {
            val back = System.currentTimeMillis()
            guideEnd = lifecycleScope.launch {
                delay(PhoneQuirks.current.frontGapMillis)
                if (hasWindowFocus()) GuideSession.end(GuideSession.End.Gone, at = back)
            }
        }
    }

    override fun onDestroy() {
        stopWatchingShizuku?.invoke()
        stopWatchingShizuku = null
        prompt?.cancel()
        sheet?.cancelAuthentication() // its callback outlives this activity otherwise, and must not act for it
        sheet = null
        OneTap.prompting.value = false // a fingerprint sheet that never called back must not keep the cover down
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
            a11yOk = debugMode != "guided" && ChalaidenAccessibility.instance != null
            setupTick++
            then()
        }
    }

    /**
     * What the automatic driver runs on: the way the user chose in Setup, if it works; with no choice yet,
     * root first, then Shizuku, then the accessibility service. Null means guided mode or setup.
     */
    private fun backend(): Backend? = when (prefs.method) {
        Method.Root -> Backend.Root.takeIf { rootOk }
        Method.Shizuku -> Backend.Shizuku.takeIf { shizukuOk }
        Method.Accessibility -> Backend.Accessibility.takeIf { a11yOk }
        Method.Guided -> null
        null -> when {
            rootOk -> Backend.Root
            shizukuOk -> Backend.Shizuku
            // Only on because the user switched it on in Settings.
            a11yOk -> Backend.Accessibility
            else -> null
        }
    }

    /** The way bKash gets answered right now, or null if none works yet. */
    private fun inUse(): Method? = when (backend()) {
        Backend.Root -> Method.Root
        Backend.Shizuku -> Method.Shizuku
        Backend.Accessibility -> Method.Accessibility
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
        // Debug builds only: dial from the number screen even with One Tap on, to try that path on a phone that has it.
        //   adb shell am start -n com.chalaiden.app/.MainActivity --ez early_dial true
        if (BuildConfig.DEBUG && intent?.hasExtra("early_dial") == true) debugEarly = intent.getBooleanExtra("early_dial", false)
        if (BuildConfig.DEBUG && intent?.hasExtra("shizuku_status") == true) {
            debugShizuku = ShizukuAccess.Status.entries.firstOrNull { it.name == intent.getStringExtra("shizuku_status") }
        }
        // Debug builds only: show the guided card for a made-up payment, without dialling.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("guide_demo", false) == true) {
            val demo = Flows.payment("01302277488", "250", null).copy(groups = listOf(5, 3, 3))
            stack.clear()
            stack += Screen.Home
            GuideSession.start(demo)
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("home") == true) {
            debugHome = DebugDemo.home(intent.getStringExtra("home").orEmpty())
            debugFontScale = intent.getFloatExtra("fontscale", 0f).takeIf { it > 0f }
            debugWidth = intent.getIntExtra("width", 0).takeIf { it > 0 }
            stack.clear(); stack += Screen.Home
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("demo") == true) {
            val target = DebugDemo.target(
                intent.getStringExtra("demo").orEmpty(),
                intent.getStringExtra("kind"),
                intent.getBooleanExtra("worst", false),
            )
            when (target) {
                is DebugDemo.Target.Run -> {
                    UssdSession.debugShow(UssdSession.Snapshot(target.flow, target.state, target.confirmed))
                    stack.clear(); stack += listOf(Screen.Home, Screen.Running)
                }
                is DebugDemo.Target.Result -> {
                    UssdSession.debugShow(null) // or the run cover stays up over the result
                    // From a run demo on screen, as a real payment arrives: with the wave.
                    if (stack.lastOrNull() == Screen.Running) lifecycleScope.launch { showResult(target.flow, target.outcome) }
                    else { stack.clear(); stack += listOf(Screen.Home, Screen.Result(target.flow, target.outcome)) }
                }
                is DebugDemo.Target.Receipt -> com.chalaiden.app.ui.writeReceipt(this, target.flow, target.outcome, java.util.Date())
                null -> Unit
            }
        }
        // Debug builds only: walk bKash's balance menu to its PIN prompt and stop there, then close it,
        // without touching the screen. HyperOS refuses `adb shell input` unless "USB debugging (Security
        // settings)" is on, which needs a Mi account (REDMI Turbo 4 Pro, 2026-11-23). No PIN is ever typed:
        // the walk waits at bKash's PIN prompt until `walk cancel`.
        //   adb shell am start -n com.chalaiden.app/.MainActivity --es walk balance|cancel
        if (BuildConfig.DEBUG && intent?.hasExtra("walk") == true) {
            when (intent.getStringExtra("walk")) {
                "balance" -> if (!UssdSession.isActive) {
                    home()
                    start(Flows.balance())
                    lifecycleScope.launch { delay(1_000); UssdSession.warmUp() }
                }
                "cancel" -> {
                    PinEntry.reset()
                    UssdSession.abandon()
                }
            }
        }
        // Debug builds only: what each SIM API tells Chalaiden, written to files/sims.log. The REDMI K20
        // Pro (MIUI 12.5, Android 11) gave Setup no SIMs with both SIMs in and phone access allowed.
        //   adb shell am start -n com.chalaiden.app/.MainActivity --ez sims_probe true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("sims_probe", false) == true) {
            java.io.File(filesDir, "sims.log").writeText(Dialer.probe(this))
        }
        // Debug builds only: a made-up newer version, to see the update sheet and the Updates screen.
        //   adb shell am start -n com.chalaiden.app/.MainActivity --es update_demo 0.2.0
        if (BuildConfig.DEBUG && intent?.hasExtra("update_demo") == true) {
            val v = intent.getStringExtra("update_demo").orEmpty().ifBlank { "9.9.9" }
            com.chalaiden.app.update.Updates.debugShow(
                this,
                com.chalaiden.app.update.Release(v, "## What's new\n- Check your balance with one tap\n- An About page in the menu\n- Works with Infinix phones", "https://github.com/inj-src/chalaiden/releases", null),
            )
            stack.clear(); stack += Screen.Home
        }
        if (BuildConfig.DEBUG && intent?.hasExtra("ussd_probe") == true) {
            com.chalaiden.app.ussd.UssdProbe.send(this, intent.getStringExtra("ussd_probe").orEmpty(), simId())
        }
        if (intent?.action == ACTION_SCAN && !UssdSession.isActive) {
            // A run staged on its amount or PIN screen (nothing confirmed) gives way to the scan.
            PinEntry.reset()
            UssdSession.abandon()
            stack.clear()
            stack += listOf(Screen.Home, Screen.Scan(FlowKind.Payment))
        }
    }

    /**
     * Replaces the run screen with its result. Money that moved gets the wave: the run screen as it looked
     * is pictured first, for the wave to carry into the result.
     */
    private suspend fun showResult(flow: UssdFlow, outcome: Outcome) {
        val wave = outcome is Outcome.Success && flow.kind != FlowKind.Balance
        if (wave) waveFrom = screenLayer?.let { runCatching { it.toImageBitmap() }.getOrNull() }
        replaceTop(Screen.Result(flow, outcome, wave))
    }

    private fun push(s: Screen) { forward = true; stack += s }
    private fun pop() { forward = false; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    private fun replaceTop(s: Screen) { forward = true; stack[stack.lastIndex] = s }
    private fun home() { forward = false; stack.clear(); stack += Screen.Home }

    private fun phoneReady() = Dialer.hasPermissions(this) && simId() != null

    /** Nothing left to set up: automatic answering works, or the user chose guided mode. */
    private fun ready() = phoneReady() && inUse() != null

    /** One Tap's one-time offer: second open or later, set up, answering bKash itself, a fingerprint on the phone. */
    private fun offerOneTap(): Boolean = !prefs.oneTapOffered && prefs.opens >= 2 && ready() &&
        inUse() != Method.Guided && !OneTap.saved.value && OneTap.readiness(this) == OneTap.Readiness.Ready

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

    /**
     * After the number (and reference): with automatic answering, bKash's menu opens now and is walked to
     * its amount prompt while the amount is typed, on the run screen ([UssdFlow.amountLater]). Guided mode
     * can't, and neither can One Tap: its fingerprint sheet opens only while nothing of bKash's is up, so
     * there the amount comes first, as before. [flow] has no amount yet.
     */
    private fun toAmount(flow: UssdFlow) {
        val oneTap = OneTap.saved.value && !OneTap.suspect.value && !debugEarly
        if (phoneReady() && backend() != null && !oneTap) return dialEarly(flow)
        val number = flow.recipient ?: return
        push(Screen.Amount(flow.kind, number, flow.groups, flow.reference?.takeUnless(Flows::isDefaultReference).orEmpty()))
    }

    /** Debug builds only: see `early_dial` in [handle]. */
    private var debugEarly = false

    private fun dialEarly(flow: UssdFlow) {
        run(flow)
        val id = runId
        lifecycleScope.launch {
            // Dialled once the cover is up over the screen sliding in, so bKash's dialog never shows.
            // Not a cover still fading out from the last run: that one is gone before bKash's dialog is.
            withTimeoutOrNull(1_000) { RunCover.covering.first { it } }
            // Gone to the background meanwhile: the phone won't dial from there. The first PIN digit will.
            if (id == runId && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) UssdSession.warmUp()
        }
    }

    /** Shows the PIN screen (or, for [UssdFlow.amountLater], the amount first). Nothing is dialled until "Hold to pay" is pressed (see [drive]). */
    private fun run(flow: UssdFlow) {
        PinEntry.reset()
        // A retry of a run whose amount was typed on the run screen keeps it, for Back from the PIN.
        AmountEntry.amount.value = flow.amount.orEmpty()
        runId++
        askedFor = null
        prompt?.cancel()
        OneTap.usedThisRun = false
        OneTap.pinNotice.value = null
        OneTap.wrongPin.value = false
        UssdSession.prepare(flow)
        // A second tap on Continue before the first screen change landed: one run screen, not two.
        if (stack.lastOrNull() != Screen.Running) push(Screen.Running)
    }

    /** Counts runs, so a fingerprint sheet that was on its way for one run never sends a PIN into the next. */
    private var runId = 0

    /** The run One Tap's sheet has opened for by itself; it opens once per run, then only when asked. */
    private var askedFor: Int? = null

    /** The fingerprint sheet on its way (waiting for the cover to go); a key press cancels it. */
    private var prompt: Job? = null

    /** The fingerprint sheet that is up, from [payWithFingerprint]. */
    private var sheet: BiometricPrompt? = null

    /**
     * One Tap: the phone's fingerprint sheet, naming what it's for. A match opens the saved PIN and sends
     * it, as "Hold to pay" would; "Use PIN" leaves the PIN pad. Only before anything is dialled: then this
     * activity is in front, and hiding the cover for the sheet shows nothing of bKash's.
     */
    private fun payWithFingerprint(flow: UssdFlow, settle: Long) {
        if (OneTap.prompting.value || !OneTap.saved.value || OneTap.suspect.value) return
        val run = runId
        val ready = {
            run == runId && stack.lastOrNull() == Screen.Running && PinEntry.pin.isEmpty() &&
                UssdSession.current.value?.let { it.flow == flow && !it.flow.needsAmount && !it.confirmed && it.state == RunState.Ready } == true
        }
        if (!ready()) return
        OneTap.prompting.value = true // the cover starts going; it only stays down while nothing is dialled
        prompt = lifecycleScope.launch {
            var handedOver = false
            try {
                delay(settle) // the PIN screen sliding in
                // The cover sits above every window, the sheet included: open only once it has gone. If it
                // hasn't, don't open a sheet nobody can see; "Use fingerprint" stays to try again.
                withTimeoutOrNull(1_000) { RunCover.onScreen.first { !it } } ?: return@launch
                // Behind another app the sheet can't open, and androidx would never call back.
                if (RunCover.onScreen.value || !ready() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                val cipher = OneTap.decrypter(this@MainActivity)
                if (cipher == null) {
                    OneTap.pinNotice.value = if (OneTap.saved.value) "Couldn't unlock your saved PIN. Type it this time. If this keeps happening, turn One Tap off and on again from the top-right menu."
                    else "Your saved PIN stopped working and was removed. Type your PIN."
                    return@launch
                }
                val amount = flow.amount?.toLongOrNull()?.let { "%,d TK".format(it) }
                val title = when (flow.kind) {
                    FlowKind.Payment -> amount?.let { "Pay $it?" } ?: "Pay?"
                    FlowKind.SendMoney -> amount?.let { "Send $it?" } ?: "Send money?"
                    FlowKind.CashOut -> amount?.let { "Cash out $it?" } ?: "Cash out?"
                    FlowKind.Balance -> "Check your balance?"
                }
                sheet = askFingerprint(
                    title = title,
                    subtitle = flow.recipient?.let { "To ${grouped(it, flow.groups ?: PhoneGroups)}" },
                    cancel = "Use PIN",
                    cipher = cipher,
                    confirm = flow.kind != FlowKind.Balance,
                ) { touch ->
                    sheet = null
                    OneTap.prompting.value = false
                    // A sheet that outlived this activity (recreated meanwhile): nothing here to act for.
                    if (isDestroyed || isFinishing) return@askFingerprint
                    when (touch) {
                        is Touch.Unlocked -> {
                            val pin = OneTap.read(this@MainActivity, touch.cipher)
                            if (pin == null) {
                                OneTap.pinNotice.value = "Couldn't unlock your saved PIN. Type it this time."
                            } else if (ready()) {
                                OneTap.usedThisRun = true
                                PinEntry.reset()
                                // As the hold does: start walking the menu, and send the PIN once bKash's summary matches.
                                UssdSession.warmUp()
                                UssdSession.confirm(pin)
                            }
                        }
                        Touch.Declined -> Unit
                        is Touch.Failed -> OneTap.pinNotice.value = "${touch.message.trimEnd('.')}. Type your PIN."
                    }
                }
                handedOver = true // only once the sheet is up: if opening it threw, finally clears the flag
            } finally {
                if (!handedOver) OneTap.prompting.value = false
            }
        }
    }

    /** [number] spaced as the app shows it everywhere else (017 4560 6306), for the fingerprint sheet. */
    private fun grouped(number: String, groups: List<Int>): String {
        if (groups.sum() != number.length) return number
        var at = 0
        return groups.joinToString(" ") { n -> number.substring(at, at + n).also { at += n } }
    }

    /**
     * Share Chalaiden: copies the installed app where Quick Share can read it, then hands it over ([target]), or
     * to Android's share sheet. The copy takes a moment for 20 MB, so it runs off the main thread.
     */
    private fun share(target: ComponentName?) {
        lifecycleScope.launch {
            val file = ShareApp.copy(this@MainActivity)
            if (isDestroyed || isFinishing) return@launch
            if (file == null) {
                Toast.makeText(this@MainActivity, "Couldn't get the app ready to share. Try again.", Toast.LENGTH_LONG).show()
            } else {
                runCatching { ShareApp.send(this@MainActivity, file, target) }
                    .onFailure { Toast.makeText(this@MainActivity, "No app here can send it.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun drive(flow: UssdFlow) {
        // Driver sweeps leftover dialogs and drives the menu; the activity dials, because the
        // USSD dialog only launches from a foreground app (OxygenOS blocks it otherwise).
        val sim = simId()
        // Root unless only Shizuku works; a backend that has gone since ends the session as not started.
        UssdDriver.start(this, flow, backend() ?: Backend.Root) {
            withContext(Dispatchers.Main) {
                // Left while the driver got ready: nothing to dial for (the driver checks this too).
                if (!UssdSession.isActive) return@withContext
                runCatching {
                    startActivity(Dialer.callIntent(this@MainActivity, sim))
                }.onFailure {
                    // The amount may have been typed since this started: retry with it.
                    val latest = UssdSession.current.value?.flow ?: flow
                    UssdSession.clear()
                    if (stack.lastOrNull() == Screen.Running) replaceTop(Screen.Result(latest, Outcome.Failed("Couldn't dial *247#. Check your SIM and phone permissions, then try again.", pinSent = false, fromBkash = false)))
                }
            }
        }
    }

    @Composable
    private fun App() {
        val session by UssdSession.current.collectAsState()
        val replayed by StepReplay.shown.collectAsState()
        LaunchedEffect(session, replayed) {
            // Left the PIN screen without paying: back to where the user came from.
            val s = session ?: return@LaunchedEffect run { if (stack.lastOrNull() == Screen.Running) pop() }
            val state = s.state
            // A payment that went through waits for the step list to finish ticking off (see StepReplay).
            if (state is RunState.Finished && stack.lastOrNull() == Screen.Running && StepReplay.caughtUp(s, replayed)) {
                PinEntry.reset() // digits typed before a failure while walking the menu
                // bKash refused the saved One Tap PIN: the result asks the user what to do with it.
                val o = state.outcome
                if (OneTap.usedThisRun) {
                    val wrong = o is Outcome.Failed && o.pinSent && OneTap.isWrongPin(o.message)
                    if (wrong) OneTap.suspect(this@MainActivity, true) else if (o is Outcome.Success) OneTap.suspect(this@MainActivity, false)
                    OneTap.wrongPin.value = wrong
                }
                showResult(s.flow, state.outcome)
                if (state.outcome is Outcome.Success && s.flow.kind == FlowKind.Payment) prefs.scanLearned = true
                UssdSession.clear()
            }
        }
        @Suppress("UNUSED_VARIABLE") val tick = setupTick // re-read readiness after root or Shizuku fixes
        // One Tap: the fingerprint sheet opens by itself as the PIN screen comes up, once per run.
        LaunchedEffect(session?.flow, stack.lastOrNull()) {
            val s = session ?: return@LaunchedEffect
            if (stack.lastOrNull() == Screen.Running && askedFor != runId && OneTap.saved.value) {
                askedFor = runId
                // bKash refused this PIN last time: no sheet by itself, another wrong PIN brings its lock closer.
                if (OneTap.suspect.value) {
                    OneTap.pinNotice.value = "bKash didn't accept your saved PIN last time. Type it here, or change it in One Tap from the top-right menu."
                } else {
                    payWithFingerprint(s.flow, settle = 350)
                }
            }
        }
        // "Use fingerprint" on the PIN screen, which may be the cover's copy: wait out the cover's fade.
        LaunchedEffect(Unit) {
            OneTap.asks.collect { UssdSession.current.value?.let { payWithFingerprint(it.flow, settle = 0) } }
        }
        BackHandler(enabled = stack.size > 1 && stack.last() != Screen.Running) { pop() }
        // On the PIN screen, back leaves it (closing a warm menu); once paying, back does nothing.
        BackHandler(enabled = stack.lastOrNull() == Screen.Running && session?.confirmed == false) {
            val s = session ?: return@BackHandler
            if (s.flow.needsAmount) UssdSession.abandon() else leavePin(s.flow)
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
        val layer = rememberGraphicsLayer().also { screenLayer = it }
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { screenTransition(initialState, targetState, forward) },
            modifier = Modifier.fillMaxSize().background(tokens.bg).drawWithContent {
                // Recorded only while paying: the one screen the success wave needs a picture of.
                if (stack.lastOrNull() != Screen.Running) return@drawWithContent drawContent()
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
            label = "screens",
        ) { screen -> saved.SaveableStateProvider(screen.toString()) { ScreenContent(screen) } }
    }

    @Composable
    private fun ScreenContent(screen: Screen) {
        when (screen) {
            Screen.Home -> DebugFrame(debugFontScale, debugWidth) {
              val fake = debugHome
              val update by Updates.state.collectAsState()
              val release = (update as? Updates.State.Available)?.release
              var sheetGone by remember { mutableStateOf(false) }
              var offering by remember { mutableStateOf(false) } // One Tap's offer, below
              var offered by remember { mutableStateOf(false) } // the update sheet waits for another time: one sheet a visit
              val updateUp = release != null && fake == null && !sheetGone && !offering && !offered && !Updates.snoozed(this)
              if (updateUp) UpdateSheet(release!!) { Updates.snooze(this); sheetGone = true }
              // One Tap is offered once, on the second open, where it can work. Decided once and held: the
              // preference only says whether to open it, so a later recomposition can't take it away. Not
              // while the update check runs, and not straight after the update sheet.
              val canOffer = stack.lastOrNull() == Screen.Home && !updateUp && !sheetGone && fake == null && update !is Updates.State.Checking &&
                  setupTick.let { offerOneTap() }
              LaunchedEffect(canOffer) {
                  if (canOffer && !offering) { offering = true; offered = true; prefs.oneTapOffered = true }
              }
              // Share Chalaiden, from the menu: a line on what to do in Quick Share, then the app goes to it.
              var sharing by remember { mutableStateOf(false) }
              val quickShare = remember { ShareApp.quickShare(this) }
              if (sharing && stack.lastOrNull() == Screen.Home) ShareSheet(
                  quickShare = quickShare != null,
                  onShare = { sharing = false; share(quickShare) },
                  onCancel = { sharing = false },
              )
              if (offering && stack.lastOrNull() == Screen.Home) OneTapOfferSheet(
                  onSetUp = { offering = false; push(Screen.OneTap()) },
                  onLater = {
                      offering = false
                      Toast.makeText(this, "You can turn on One Tap any time from the top-right menu.", Toast.LENGTH_LONG).show()
                  },
              )
              HomeScreen(
                sims = fake?.sims ?: Dialer.sims(this),
                simId = if (fake != null) fake.simId else setupTick.let { simId() }, // re-read after a SIM change
                onSelectSim = { prefs.simSubscriptionId = it; setupTick++ },
                setupDone = fake?.setupDone ?: setupTick.let { ready() }, // re-read once a slow root check ends
                keyboardOff = fake?.keyboardOff ?: setupTick.let { keyboardOff() },
                onFixKeyboard = { runCatching { startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)) } },
                onPay = { push(Screen.Scan(FlowKind.Payment)) },
                onSend = { push(Screen.Number(FlowKind.SendMoney, "", null)) },
                onCashOut = { push(Screen.Number(FlowKind.CashOut, "", null)) },
                onBalance = { start(Flows.balance()) },
                onSetup = { push(Screen.Setup(then = null)) },
                onRedoSetup = { push(Screen.Setup(then = null, fromStart = true)) },
                onOneTap = { push(Screen.OneTap()) },
                onShare = { sharing = true },
                onAbout = { push(Screen.About) },
                onUpdates = { push(Screen.Updates) },
                updateAvailable = release != null,
                design = prefs.homeDesign,
                onDesign = { prefs.homeDesign = it },
              )
            }
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
                onContinue = { number, reference ->
                    toAmount(
                        when (screen.kind) {
                            FlowKind.Payment -> Flows.payment(number, null, reference)
                            FlowKind.SendMoney -> Flows.sendMoney(number, null, reference)
                            else -> Flows.cashOut(number, null)
                        }.copy(groups = screen.groups),
                    )
                },
                onBack = ::pop,
            )
            is Screen.Amount -> {
              val amount = rememberSaveable { mutableStateOf("") }
              AmountScreen(
                kind = screen.kind,
                number = screen.number,
                groups = screen.groups,
                entry = amount,
                onPay = { amount ->
                    start(
                        when (screen.kind) {
                            FlowKind.Payment -> Flows.payment(screen.number, amount, screen.reference)
                            FlowKind.SendMoney -> Flows.sendMoney(screen.number, amount, screen.reference)
                            FlowKind.CashOut -> Flows.cashOut(screen.number, amount)
                            FlowKind.Balance -> Flows.balance()
                        }.copy(groups = screen.groups),
                    )
                },
                onBack = ::pop,
              )
            }
            // The same screen the cover shows over the phone's dialogs; seen here if the cover can't be drawn.
            Screen.Running -> RunOverlay()
            is Screen.Result -> {
              val wrongPin by OneTap.wrongPin.collectAsState()
              // Let the result land first: bKash's last dialog closes and the cover fades, then the sheet rises.
              var settled by remember { mutableStateOf(false) }
              LaunchedEffect(wrongPin) {
                  if (!wrongPin) return@LaunchedEffect
                  UssdDriver.dialogUp.first { !it }
                  withTimeoutOrNull(1_000) { RunCover.onScreen.first { !it } }
                  delay(200)
                  settled = true
              }
              // Only while this result is the screen: not over Home, after Done, as it slides away.
              if (wrongPin && settled && stack.lastOrNull() == screen) WrongPinSheet(
                  onChange = { OneTap.wrongPin.value = false; home(); push(Screen.OneTap(change = true)) },
                  onRemove = { OneTap.wrongPin.value = false; OneTap.forget(this) },
                  onKeep = { OneTap.wrongPin.value = false },
              )
              ResultScreen(
                flow = screen.flow,
                outcome = screen.outcome,
                // A run that failed before its amount was typed starts again from the amount.
                onRetry = { pop(); if (screen.flow.needsAmount) toAmount(screen.flow) else start(screen.flow) },
                onCheckBalance = { home(); start(Flows.balance()) },
                onDone = ::home,
                wave = screen.wave,
                waveFrom = waveFrom,
                onWaveDone = { waveFrom = null },
              )
            }
            is Screen.Setup -> SetupScreen(
                prefs = prefs,
                fromStart = screen.fromStart,
                rootOk = rootOk,
                shizukuOk = shizukuOk,
                a11yOk = a11yOk,
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
                a11yOk = a11yOk,
                inUse = setupTick.let { inUse() },
                onRecheck = { checkSetup() },
                onUse = { prefs.method = it; checkSetup(); pop() },
                onBack = ::pop,
            )
            Screen.About -> AboutScreen(onBack = ::pop)
            Screen.Updates -> UpdatesScreen(onBack = ::pop)
            is Screen.OneTap -> OneTapScreen(guided = setupTick.let { inUse() } == Method.Guided, change = screen.change, onBack = ::pop)
        }
    }

    /**
     * Shared-axis motion: the new screen slides a short way in from the side it comes from while
     * fading up; the old one drifts the other way and fades out. Results arrive with a gentle scale.
     */
    private fun AnimatedContentTransitionScope<Screen>.screenTransition(from: Screen, to: Screen, forward: Boolean): ContentTransform {
        val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        // The wave is the whole transition: it starts from a picture of the screen it replaces.
        if (to is Screen.Result && to.wave) return EnterTransition.None togetherWith ExitTransition.None
        if (to is Screen.Result) {
            return (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(300, easing = ease), initialScale = 0.96f))
                .togetherWith(fadeOut(tween(120)))
        }
        val dir = if (forward) 1 else -1
        // Under 300ms: this runs on nearly every tap, and slower motion makes the whole app feel slow.
        return (slideInHorizontally(tween(300, easing = ease)) { dir * it / 7 } + fadeIn(tween(200, delayMillis = 50)))
            .togetherWith(slideOutHorizontally(tween(300, easing = ease)) { -dir * it / 12 } + fadeOut(tween(120)))
            .apply { targetContentZIndex = if (forward) 1f else 0f }
    }

    companion object {
        const val ACTION_SCAN = "com.chalaiden.app.SCAN"
    }
}

/**
 * Debug builds only: lays [content] out as if the phone's text size were [fontScale] and the window
 * [width] dp wide, centred in the real one. Both null: [content] as it is.
 */
@androidx.compose.runtime.Composable
private fun DebugFrame(fontScale: Float?, width: Int?, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (fontScale == null && width == null) return content()
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale ?: d.fontScale),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            androidx.compose.foundation.layout.Box(
                width?.let { androidx.compose.ui.Modifier.width(it.dp) } ?: androidx.compose.ui.Modifier.fillMaxSize(),
            ) { content() }
        }
    }
}

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
import kotlinx.coroutines.withContext
import com.chalaiden.app.ui.AmountScreen
import com.chalaiden.app.ui.HomeScreen
import com.chalaiden.app.ui.ManualScreen
import com.chalaiden.app.ui.NumberScreen
import com.chalaiden.app.ui.ResultScreen
import com.chalaiden.app.ui.RunCover
import com.chalaiden.app.ui.PinEntry
import com.chalaiden.app.ui.RunOverlay
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ui.ScanScreen
import com.chalaiden.app.ui.SetupScreen
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ussd.Dialer
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.Prefs
import com.chalaiden.app.ussd.Root
import com.chalaiden.app.ussd.RootUssdDriver
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
    data class Manual(val flow: UssdFlow) : Screen
}

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val prefs by lazy { Prefs(this) }

    /** Bumped when setup changes outside Compose (root fixes), so screens re-check readiness. */
    private var setupTick by mutableIntStateOf(0)

    /** Cached result of the last root check; readiness reads this instead of spawning `su` in composition. */
    @Volatile private var rootOk = false

    /** Which way the last navigation went, so screens slide in from the right side. */
    private var forward by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        RunCover.attach(this)
        // "Hold to pay" is pressed on the cover, but only a foreground activity can dial *247#.
        UssdSession.starter = { flow -> drive(flow) }
        handle(intent)
        setContent { ChalaidenTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        checkRoot()
    }

    /** Grants what root can grant (permissions, the overlay) and refreshes readiness. Never enables Accessibility. */
    private fun checkRoot(then: () -> Unit = {}) {
        lifecycleScope.launch {
            rootOk = withContext(Dispatchers.IO) { Root.ensureSetup(this@MainActivity) }
            setupTick++
            then()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_SCAN && !UssdSession.isActive) {
            stack.clear()
            stack += listOf(Screen.Home, Screen.Scan(FlowKind.Payment))
        }
    }

    private fun push(s: Screen) { forward = true; stack += s }
    private fun pop() { forward = false; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    private fun replaceTop(s: Screen) { forward = true; stack[stack.lastIndex] = s }
    private fun home() { forward = false; stack.clear(); stack += Screen.Home }

    private fun ready() = Dialer.hasPermissions(this) && simId() != null && rootOk

    private fun simId(): Int? {
        val sims = Dialer.sims(this)
        return prefs.simSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } }
            ?: sims.singleOrNull()?.subscriptionId
    }

    private fun start(flow: UssdFlow) {
        if (ready()) return run(flow)
        checkRoot { if (ready()) run(flow) else push(Screen.Setup(then = flow)) }
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
        RootUssdDriver.start(this, flow) {
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
        @Suppress("UNUSED_VARIABLE") val tick = setupTick // re-read readiness after root fixes
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
                onPay = { push(Screen.Scan(FlowKind.Payment)) },
                onSend = { push(Screen.Number(FlowKind.SendMoney, "", null)) },
                onCashOut = { push(Screen.Number(FlowKind.CashOut, "", null)) },
                onBalance = { start(Flows.balance()) },
                onSetup = { push(Screen.Setup(then = null)) },
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
                onRetryRoot = { checkRoot() },
                onDone = { pop(); screen.then?.let(::start) },
                onManual = screen.then?.let { flow -> { replaceTop(Screen.Manual(flow)) } },
                onBack = ::pop,
            )
            is Screen.Manual -> ManualScreen(screen.flow, simId(), onBack = ::pop)
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

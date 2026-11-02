package com.chalaiden.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chalaiden.app.ui.AmountScreen
import com.chalaiden.app.ui.HomeScreen
import com.chalaiden.app.ui.ManualScreen
import com.chalaiden.app.ui.NumberScreen
import com.chalaiden.app.ui.ResultScreen
import com.chalaiden.app.ui.ScanScreen
import com.chalaiden.app.ui.SetupScreen
import com.chalaiden.app.ui.components.Spinner
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.Dialer
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.Prefs
import com.chalaiden.app.ussd.Root
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdFlow
import com.chalaiden.app.ussd.UssdService
import com.chalaiden.app.ussd.UssdSession

private sealed interface Screen {
    data object Home : Screen
    data class Scan(val kind: FlowKind) : Screen
    data class Number(val kind: FlowKind, val number: String, val crop: Bitmap?) : Screen
    data class Amount(val kind: FlowKind, val number: String) : Screen
    data object Running : Screen
    data class Result(val flow: UssdFlow, val outcome: Outcome) : Screen
    data class Setup(val then: UssdFlow?) : Screen
    data class Manual(val flow: UssdFlow) : Screen
}

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())

    /** Bumped when setup changes outside Compose (root fixes), so screens re-check readiness. */
    private var setupTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        setContent { ChalaidenTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        // On a rooted phone, quietly put back anything Android took away (permissions,
        // the Accessibility service after a force-stop), then refresh the screens.
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { Root.ensureSetup(this@MainActivity) }) setupTick++
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

    private fun push(s: Screen) { stack += s }
    private fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    private fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    private fun home() { stack.clear(); stack += Screen.Home }

    private fun ready() = Dialer.hasPermissions(this) && simId() != null && UssdService.isEnabled(this)

    private fun simId(): Int? {
        val sims = Dialer.sims(this)
        return prefs.simSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } }
            ?: sims.singleOrNull()?.subscriptionId
    }

    private fun start(flow: UssdFlow) {
        if (ready()) return run(flow)
        lifecycleScope.launch {
            val fixed = withContext(Dispatchers.IO) { Root.ensureSetup(this@MainActivity) }
            setupTick++
            if (fixed && ready()) run(flow) else push(Screen.Setup(then = flow))
        }
    }

    private fun run(flow: UssdFlow) {
        push(Screen.Running)
        val go: () -> Unit = {
            // Begin first so the overlay is up before the phone app shows its first dialog.
            UssdSession.begin(flow)
            main.postDelayed({
                try {
                    Dialer.dial(this, simId())
                } catch (e: Exception) {
                    UssdSession.clear()
                    replaceTop(Screen.Result(flow, Outcome.Failed("Couldn't open the bKash menu: ${e.message}", pinSent = false)))
                }
            }, OVERLAY_HEAD_START_MILLIS)
            Unit
        }
        UssdSession.sweeper?.invoke(go) ?: go()
    }

    @Composable
    private fun App() {
        val session by UssdSession.current.collectAsState()
        LaunchedEffect(session) {
            val s = session ?: return@LaunchedEffect
            val state = s.state
            if (state is RunState.Finished && stack.lastOrNull() == Screen.Running) {
                replaceTop(Screen.Result(s.flow, state.outcome))
                UssdSession.clear()
            }
        }
        @Suppress("UNUSED_VARIABLE") val tick = setupTick // re-read readiness after root fixes
        BackHandler(enabled = stack.size > 1 && stack.last() != Screen.Running) { pop() }

        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(
                simLabel = Dialer.sims(this).firstOrNull { it.subscriptionId == simId() }?.let { "${it.carrier} · SIM ${it.slot + 1}" },
                setupDone = ready(),
                onScan = { push(Screen.Scan(FlowKind.Payment)) },
                onType = { push(Screen.Number(FlowKind.Payment, "", null)) },
                onSend = { push(Screen.Number(FlowKind.SendMoney, "", null)) },
                onBalance = { start(Flows.balance()) },
                onSetup = { push(Screen.Setup(then = null)) },
            )
            is Screen.Scan -> ScanScreen(
                onRead = { read ->
                    pop()
                    val next = Screen.Number(screen.kind, read.number.orEmpty(), read.crop)
                    if ((stack.last() as? Screen.Number)?.kind == screen.kind) replaceTop(next) else push(next)
                },
                onTypeInstead = { replaceTop(Screen.Number(screen.kind, "", null)) },
                onBack = ::pop,
            )
            is Screen.Number -> NumberScreen(
                kind = screen.kind,
                initial = screen.number,
                crop = screen.crop,
                onScan = { push(Screen.Scan(screen.kind)) },
                onContinue = { push(Screen.Amount(screen.kind, it)) },
                onBack = ::pop,
            )
            is Screen.Amount -> AmountScreen(
                kind = screen.kind,
                number = screen.number,
                onPay = { amount, reference ->
                    start(
                        if (screen.kind == FlowKind.Payment) Flows.payment(screen.number, amount, reference)
                        else Flows.sendMoney(screen.number, amount, reference),
                    )
                },
                onBack = ::pop,
            )
            Screen.Running -> Box(Modifier.fillMaxSize().background(tokens.bg), contentAlignment = Alignment.Center) {
                Spinner(tokens.accent, 48.dp, stroke = 3.5.dp, track = tokens.raised)
            }
            is Screen.Result -> ResultScreen(
                flow = screen.flow,
                outcome = screen.outcome,
                onRetry = { pop(); start(screen.flow) },
                onCheckBalance = { home(); start(Flows.balance()) },
                onDone = ::home,
            )
            is Screen.Setup -> SetupScreen(
                prefs = prefs,
                onDone = { pop(); screen.then?.let(::start) },
                onManual = screen.then?.let { flow -> { replaceTop(Screen.Manual(flow)) } },
                onBack = ::pop,
            )
            is Screen.Manual -> ManualScreen(screen.flow, simId(), onBack = ::pop)
        }
    }

    companion object {
        const val ACTION_SCAN = "com.chalaiden.app.SCAN"
        private const val OVERLAY_HEAD_START_MILLIS = 200L
    }
}

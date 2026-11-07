package com.chalaiden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.components.AnimatedCheck
import com.chalaiden.app.ui.components.HoldToConfirm
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.PhoneGroups
import com.chalaiden.app.ui.components.PinDots
import com.chalaiden.app.ui.components.PopIn
import com.chalaiden.app.ui.components.Spinner
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.Step
import com.chalaiden.app.ussd.UssdFlow
import com.chalaiden.app.ussd.UssdSession
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 5

/**
 * The PIN being typed. Shared on purpose: the activity and the cover both draw this screen, and the
 * cover takes over a moment after it opens, so digits typed in between must carry over. Memory only.
 */
object PinEntry {
    var pin by mutableStateOf("")
    fun reset() { pin = "" }
}

/**
 * The run screen: PIN entry first, then progress while Chalaiden walks bKash's menu, then bKash's
 * answer. Drawn by the activity and by the cover ([RunCover]) above the phone's USSD dialogs.
 */
@Composable
fun RunOverlay() {
    val snap by UssdSession.current.collectAsState()
    val t = tokens
    val s = snap
    if (s == null || s.state is RunState.Finished) {
        // Between the end of a session and the phone's last dialog closing (or a trailing bKash prompt).
        Box(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) { Waiting("Closing the bKash menu…") }
        return
    }
    val phase = when {
        !s.confirmed -> Phase.Pin
        s.state is RunState.Working -> Phase.Progress
        (s.state as? RunState.AwaitingPin)?.feeToConfirm != null -> Phase.Fee
        else -> Phase.Confirming
    }
    Box(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            // On the fee step bKash's own summary lists the recipient, so the rail would only repeat it.
            Header(s.flow, running = s.confirmed, showRecipient = phase != Phase.Fee)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 14 })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "run",
                modifier = Modifier.weight(1f),
            ) { p ->
                when (p) {
                    Phase.Pin -> PinStep(s.flow)
                    Phase.Progress -> Progress(s.flow, (s.state as? RunState.Working)?.stepIndex ?: 0)
                    Phase.Fee -> (s.state as? RunState.AwaitingPin)?.let { FeeStep(s.flow, it) } ?: Waiting("")
                    Phase.Confirming -> Waiting("Confirming with bKash…")
                }
            }
        }
    }
}

private enum class Phase { Pin, Progress, Fee, Confirming }

@Composable
private fun Header(flow: UssdFlow, running: Boolean, showRecipient: Boolean) {
    val t = tokens
    val verb = when (flow.kind) {
        FlowKind.Payment -> if (running) "Paying" else "Pay"
        FlowKind.SendMoney -> if (running) "Sending" else "Send"
        FlowKind.CashOut -> if (running) "Cashing out" else "Cash out"
        FlowKind.Balance -> "Checking balance"
    }
    Text(verb, style = Type.title, color = t.inkSoft)
    if (flow.amount != null) {
        Text("Tk ${flow.amount}", style = Type.amount, color = t.ink)
    }
    flow.recipient?.takeIf { showRecipient }?.let {
        Spacer(Modifier.height(12.dp))
        NumberRail(it, groups = flow.groups ?: PhoneGroups, compact = true)
    }
}

private fun holdLabel(kind: FlowKind) = when (kind) {
    FlowKind.Payment -> "Hold to pay"
    FlowKind.SendMoney -> "Hold to send"
    FlowKind.CashOut -> "Hold to cash out"
    FlowKind.Balance -> "Hold to check"
}

/**
 * PIN first, one hold. The first digit starts walking bKash's menu, so its summary is usually up by
 * the time the hold completes. The PIN goes in only once bKash's summary matches; letting go of the
 * hold early leaves the menu warm, and Cancel or back closes it.
 */
@Composable
private fun PinStep(flow: UssdFlow) {
    val t = tokens
    val pin = PinEntry.pin
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            flow.reference?.takeIf { !Flows.isDefaultReference(it) }?.let { Detail("Reference", it) }
            val note = when (flow.kind) {
                FlowKind.CashOut -> "bKash adds its cash out charge to this."
                FlowKind.Balance -> "Enter your PIN to see your balance."
                else -> null
            }
            note?.let { Text(it, style = Type.body, color = t.inkSoft) }
        }
        Spacer(Modifier.weight(1f))
        PinDots(pin.length, PIN_LENGTH, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
        Keypad(
            onKey = {
                if (PinEntry.pin.length < PIN_LENGTH) PinEntry.pin += it
                UssdSession.warmUp()
            },
            onBackspace = { PinEntry.pin = PinEntry.pin.dropLast(1) },
        )
        Spacer(Modifier.height(12.dp))
        HoldToConfirm(
            holdLabel(flow.kind),
            enabled = pin.length == PIN_LENGTH,
            // Normally already warm from the first digit; dials again if bKash dropped an idle menu.
            onHoldStart = UssdSession::warmUp,
        ) {
            val entered = PinEntry.pin
            if (entered.length != PIN_LENGTH) return@HoldToConfirm
            PinEntry.reset()
            UssdSession.confirm(entered)
        }
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
            PinEntry.reset()
            UssdSession.abandon()
        }
    }
}

@Composable
private fun Detail(key: String, value: String) {
    val t = tokens
    Row {
        Text(key, style = Type.body, color = t.inkSoft, modifier = Modifier.weight(1f))
        Text(value, style = Type.body.copy(fontFamily = Type.button.fontFamily), color = t.ink)
    }
}

@Composable
private fun Progress(flow: UssdFlow, current: Int) {
    val t = tokens
    val labels = listOf("Open bKash menu") + flow.steps.dropLast(1).map {
        when (it) {
            is Step.Pick -> "Choose ${it.label}"
            is Step.Answer -> it.describe.replaceFirstChar { c -> c.uppercase() }
            Step.Pin -> ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            labels.forEachIndexed { i, label ->
                val done = i < current
                val active = i == current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            done -> PopIn {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
                                    AnimatedCheck(t.onAccent, 16.dp, stroke = 2.2.dp)
                                }
                            }
                            active -> Spinner(t.accent, 22.dp, stroke = 2.5.dp, track = t.raised)
                            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(t.raised))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = Type.body, color = if (done || active) t.ink else t.inkSoft)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally)) { UssdSession.cancel() }
    }
}

/** bKash added a fee the user hasn't seen: show its summary and ask once more. The PIN is already entered. */
@Composable
private fun FeeStep(flow: UssdFlow, state: RunState.AwaitingPin) {
    val t = tokens
    var left by remember { mutableLongStateOf(UssdSession.PIN_WINDOW_MILLIS) }
    LaunchedEffect(state.sinceMillis) {
        while (true) {
            left = (state.sinceMillis + UssdSession.PIN_WINDOW_MILLIS - System.currentTimeMillis()).coerceAtLeast(0)
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("bKash charges ${state.feeToConfirm} for this.", style = Type.title, color = t.ink)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.details
                .filterNot { (k, v) -> k.equals("Reference", ignoreCase = true) && Flows.isDefaultReference(v) }
                .forEach { (k, v) -> Detail(k, v) }
        }
        Spacer(Modifier.weight(1f))
        HoldToConfirm(holdLabel(flow.kind), enabled = left > 0) { UssdSession.confirmFee() }
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) { UssdSession.cancel() }
        val secs = (left / 1000).toInt()
        Text(
            if (secs > 0) "Closes in %d:%02d".format(secs / 60, secs % 60) else "bKash closed this",
            style = Type.label.copy(fontSize = 12.sp),
            color = if (secs in 1..20) t.accent else t.inkSoft,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Waiting(text: String) {
    val t = tokens
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Spinner(t.accent, 48.dp, stroke = 3.5.dp, track = t.raised)
        Spacer(Modifier.height(18.dp))
        Text(text, style = Type.title, color = t.ink)
        Text("Don't close Chalaiden.", style = Type.body, color = t.inkSoft)
    }
}

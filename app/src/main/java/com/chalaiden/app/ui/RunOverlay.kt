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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import com.chalaiden.app.ui.components.AppIcons
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.components.AnimatedCheck
import com.chalaiden.app.ui.components.HoldToConfirm
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.AmountText
import com.chalaiden.app.ui.components.DetailRow
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
            Header(s.flow, running = s.confirmed)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 50)) + slideInVertically(tween(260)) { it / 14 })
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
private fun Header(flow: UssdFlow, running: Boolean) {
    val t = tokens
    val verb = when (flow.kind) {
        FlowKind.Payment -> if (running) "Paying" else "Pay"
        FlowKind.SendMoney -> if (running) "Sending" else "Send"
        FlowKind.CashOut -> if (running) "Cashing out" else "Cash out"
        FlowKind.Balance -> "Checking balance"
    }
    Text(verb, style = Type.title, color = t.inkSoft)
    if (flow.amount != null) {
        AmountText("%,d".format(flow.amount.toLongOrNull() ?: 0))
    }
    flow.recipient?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            when (flow.kind) { FlowKind.Payment -> "To shop"; FlowKind.CashOut -> "To agent"; else -> "To" },
            style = Type.label, color = t.inkSoft,
        )
        Spacer(Modifier.height(2.dp))
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
        // Details, then the dots and where the PIN goes, right under the amount; the keypad takes the rest
        // and sits on the hold button. The details scroll and the keys shrink (to 40dp) before "Hold to pay"
        // is pushed off: at 200% text on a small phone all of it must still be reachable.
        val note = when (flow.kind) {
            FlowKind.CashOut -> "bKash adds its cash out charge to this."
            FlowKind.Balance -> "Enter your PIN to see your balance."
            else -> null
        }
        val reference = flow.reference?.takeIf { !Flows.isDefaultReference(it) }
        if (reference != null || note != null) {
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reference?.let { DetailRow("Reference", it) }
                note?.let { Text(it, style = Type.body, color = t.inkSoft) }
            }
        }
        Spacer(Modifier.height(if (reference != null || note != null) 24.dp else 8.dp))
        PinDots(pin.length, PIN_LENGTH, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Lock, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Goes only to bKash. Never saved.", style = Type.label.copy(fontSize = 13.sp), color = t.inkSoft, maxLines = 1)
        }
        Keypad(
            onKey = {
                if (PinEntry.pin.length < PIN_LENGTH) PinEntry.pin += it
                UssdSession.warmUp()
            },
            onBackspace = { PinEntry.pin = PinEntry.pin.dropLast(1) },
            modifier = Modifier.weight(1f).padding(top = 16.dp),
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
private fun Progress(flow: UssdFlow, current: Int) {
    val t = tokens
    // Each line says what is happening ("Entering amount"), then, once done, what happened ("Entered amount").
    val labels: List<Pair<String, String>> = listOf("Opening bKash menu" to "Opened bKash menu") + flow.steps.dropLast(1).map {
        when (it) {
            is Step.Pick -> "Choosing ${it.label}" to "Chose ${it.label}"
            is Step.Answer -> it.describe.replaceFirstChar { c -> c.uppercase() }.let { now -> now to pastTense(now) }
            Step.Pin -> "" to ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        // The list sits in the upper part of the free space, not hung from the header with a void below.
        Spacer(Modifier.weight(0.4f))
        Column(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            labels.forEachIndexed { i, (now, past) ->
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
                    Text(if (done) past else now, style = Type.body, color = if (done || active) t.ink else t.inkSoft)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Down by Cancel, where the eye and thumb already are.
        Text(
            "Keep Chalaiden open · about 10 s",
            style = Type.label, color = t.inkSoft, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally)) { UssdSession.cancel() }
    }
}

/** "Entering amount" → "Entered amount", "Adding reference" → "Added reference". */
private fun pastTense(now: String): String = when {
    now.startsWith("Entering ") -> "Entered " + now.removePrefix("Entering ")
    now.startsWith("Adding ") -> "Added " + now.removePrefix("Adding ")
    else -> now
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
        // bKash's summary, as a table: the Charge row says what's new, the Total what leaves the account.
        // It takes the height, so the hold sits at the bottom like on every other screen.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The number already sits under the amount, as on every other screen; the table is the money.
            val rows = state.details
                .filterNot { (k, v) -> v.isBlank() || (k.equals("Reference", ignoreCase = true) && Flows.isDefaultReference(v)) }
                .filterNot { (_, v) -> v.filter(Char::isDigit) == flow.recipient }
            rows.forEach { (k, v) ->
                // The total is what actually leaves the account: set apart by a rule and title type.
                val total = k.equals("Total", ignoreCase = true)
                if (total) Box(Modifier.padding(top = 16.dp, bottom = 4.dp).fillMaxWidth().height(1.dp).background(t.line))
                DetailRow(k, v, strong = total)
            }
        }
        val secs = (left / 1000).toInt()
        Text(
            if (secs > 0) "bKash closes this in %d:%02d".format(secs / 60, secs % 60) else "bKash closed this",
            style = Type.label,
            color = if (secs in 1..20) t.error else t.inkSoft,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
        HoldToConfirm(holdLabel(flow.kind), enabled = left > 0) { UssdSession.confirmFee() }
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) { UssdSession.cancel() }
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

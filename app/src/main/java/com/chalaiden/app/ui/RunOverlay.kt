package com.chalaiden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.HoldToConfirm
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.PinDots
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.Step
import com.chalaiden.app.ussd.UssdFlow
import com.chalaiden.app.ussd.UssdSession
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 5

/** Full-screen cover drawn above the phone app's USSD dialogs while a session runs. */
@Composable
fun RunOverlay() {
    val snap by UssdSession.current.collectAsState()
    val s = snap ?: return
    val t = tokens
    Box(Modifier.fillMaxSize().background(t.teal).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Header(s.flow)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(targetState = s.state::class, label = "run", modifier = Modifier.weight(1f)) {
                when (val state = s.state) {
                    is RunState.Working -> Progress(s.flow, state.stepIndex)
                    is RunState.AwaitingPin -> PinStep(s.flow, state)
                    RunState.Confirming -> Waiting("Confirming with bKash…")
                    is RunState.Finished -> Waiting("")
                }
            }
        }
    }
}

@Composable
private fun Header(flow: UssdFlow) {
    val t = tokens
    val verb = when (flow.kind) {
        FlowKind.Payment -> "Paying"
        FlowKind.SendMoney -> "Sending"
        FlowKind.Balance -> "Checking balance"
    }
    Text(verb, style = Type.title, color = t.onTeal.copy(alpha = 0.8f))
    if (flow.amount != null) {
        Text("Tk ${flow.amount}", style = Type.amount, color = t.onTeal)
    }
    flow.recipient?.let {
        Spacer(Modifier.height(12.dp))
        NumberRail(it, compact = true, onTeal = true)
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
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            labels.forEachIndexed { i, label ->
                val done = i < current
                val active = i == current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (done) t.marigold else t.onTeal.copy(alpha = if (active) 0.9f else 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Text("✓", style = Type.label, color = t.onMarigold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = Type.body, color = t.onTeal.copy(alpha = if (done || active) 1f else 0.55f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Cancel",
            style = Type.button,
            color = t.onTeal,
            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp))
                .clickable { UssdSession.cancel() }.padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun PinStep(flow: UssdFlow, state: RunState.AwaitingPin) {
    val t = tokens
    var pin by remember { mutableStateOf("") }
    var left by remember { mutableLongStateOf(UssdSession.PIN_WINDOW_MILLIS) }
    LaunchedEffect(state.sinceMillis) {
        while (true) {
            left = (state.sinceMillis + UssdSession.PIN_WINDOW_MILLIS - System.currentTimeMillis()).coerceAtLeast(0)
            delay(250)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.onTeal.copy(alpha = 0.1f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("bKash shows", style = Type.label, color = t.onTeal.copy(alpha = 0.75f))
            if (state.details.isEmpty()) {
                Text("Enter your PIN to continue.", style = Type.body, color = t.onTeal)
            }
            state.details.forEach { (k, v) ->
                Row {
                    Text(k, style = Type.body, color = t.onTeal.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                    Text(v, style = Type.body.copy(fontFamily = Type.button.fontFamily), color = t.onTeal)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PinDots(pin.length, PIN_LENGTH, t.onTeal)
            Spacer(Modifier.weight(1f))
            val secs = (left / 1000).toInt()
            Text(
                if (secs > 0) "Closes in %d:%02d".format(secs / 60, secs % 60) else "bKash may have closed this",
                style = Type.label,
                color = if (secs in 1..20) t.marigold else t.onTeal.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.weight(1f))
        Keypad(
            onKey = { if (pin.length < PIN_LENGTH) pin += it },
            onBackspace = { pin = pin.dropLast(1) },
            dark = true,
        )
        Spacer(Modifier.height(12.dp))
        val verb = when (flow.kind) {
            FlowKind.Payment -> "Hold to pay"
            FlowKind.SendMoney -> "Hold to send"
            FlowKind.Balance -> "Hold to check"
        }
        HoldToConfirm(verb, enabled = pin.length == PIN_LENGTH) {
            val entered = pin
            pin = ""
            UssdSession.submitPin(entered)
        }
        Text(
            "Cancel",
            style = Type.button,
            color = t.onTeal,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                .clickable { pin = ""; UssdSession.cancel() }.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Waiting(text: String) {
    val t = tokens
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = t.marigold)
        Spacer(Modifier.height(18.dp))
        Text(text, style = Type.title, color = t.onTeal)
        Text("Don't close Chalaiden.", style = Type.body, color = t.onTeal.copy(alpha = 0.75f))
    }
}

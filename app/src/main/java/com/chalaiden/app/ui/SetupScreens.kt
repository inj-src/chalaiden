package com.chalaiden.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.Dialer
import com.chalaiden.app.ussd.Prefs
import com.chalaiden.app.ussd.Sim
import com.chalaiden.app.ussd.Step
import com.chalaiden.app.ussd.UssdFlow

/** Three things Chalaiden needs before it can answer bKash by itself, in the order it needs them. */
@Composable
fun SetupScreen(prefs: Prefs, rootOk: Boolean, onRetryRoot: () -> Unit, onDone: () -> Unit, onManual: (() -> Unit)?, onBack: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // bumps on resume so checks re-run
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val phoneOk = remember(tick) { Dialer.hasPermissions(context) }
    val sims = remember(tick) { Dialer.sims(context) }
    var simId by remember(tick) { mutableStateOf(prefs.simSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } }) }
    if (simId == null && sims.size == 1) { simId = sims.first().subscriptionId; prefs.simSubscriptionId = simId }

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar("Set up", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SetupStep(1, "Phone access", phoneOk,
                "Lets Chalaiden open the bKash menu (*247#) and see which SIMs you have.") {
                if (!phoneOk) PrimaryButton("Allow phone access") { ask.launch(Dialer.permissions) }
            }
            SetupStep(2, "bKash SIM", simId != null, "The SIM your bKash account is on.") {
                if (phoneOk) sims.forEach { sim -> SimRow(sim, sim.subscriptionId == simId) { simId = sim.subscriptionId; prefs.simSubscriptionId = simId } }
                if (phoneOk && sims.isEmpty()) Text("No SIM found. Insert your bKash SIM.", style = Type.body, color = t.error)
            }
            SetupStep(3, "Root access", rootOk,
                "Chalaiden answers the bKash *247# menu for you with root, behind its own screen. " +
                    "No Accessibility service is used, so the bKash app keeps working. Your PIN is never saved.") {
                if (!rootOk) {
                    PrimaryButton("Grant root", onClick = onRetryRoot)
                    Spacer(Modifier.height(8.dp))
                    Text("Allow Chalaiden in your root manager (APatch, Magisk, KernelSU) when it asks.",
                        style = Type.label, color = t.inkSoft)
                }
            }
        }
        Column(Modifier.padding(20.dp)) {
            PrimaryButton("Done", enabled = phoneOk && simId != null && rootOk, onClick = onDone)
            if (onManual != null && !rootOk) {
                Text("Do it by hand this time", style = Type.button, color = t.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onManual).padding(14.dp))
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, done: Boolean, body: String, content: @Composable () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (done) t.accent else t.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(AppIcons.Check, null, tint = t.onAccent, modifier = Modifier.size(17.dp))
            else Text("$n", style = Type.label, color = t.ink)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Type.title, color = t.ink)
            Text(body, style = Type.body, color = t.inkSoft)
            if (!done || title == "bKash SIM") content()
        }
    }
}

@Composable
private fun SimRow(sim: Sim, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.surface)
            .border(2.dp, if (selected) t.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sim.carrier, style = Type.body, color = t.ink, modifier = Modifier.weight(1f))
        Text("SIM ${sim.slot + 1}", style = Type.label, color = t.inkSoft)
    }
}

/** Menu positions seen on Grameenphone, 2026-11-02. Only a hint; the automatic flow picks by label. */
private val MENU_NUMBERS = mapOf(
    "Payment" to "4", "Send Money" to "1", "Cash Out" to "5", "From Agent" to "1",
    "My bKash" to "9", "Check Balance" to "1",
)

/** Fallback when automatic answering is off: the exact replies to type, in order. */
@Composable
fun ManualScreen(flow: UssdFlow, simId: Int?, onBack: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val replies = flow.steps.mapNotNull {
        when (it) {
            is Step.Pick -> "Choose ${it.label}" to (MENU_NUMBERS[it.label] ?: it.label)
            is Step.Answer -> it.describe to it.value
            Step.Pin -> null
        }
    }
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar("Do it by hand", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Remember these, then open the bKash menu and type each one.", style = Type.body, color = t.inkSoft)
            Spacer(Modifier.height(16.dp))
            replies.forEachIndexed { i, (what, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = Type.title, color = t.inkSoft, modifier = Modifier.size(32.dp))
                    Column {
                        Text(what, style = Type.label, color = t.inkSoft)
                        Text(value, style = Type.title, color = t.ink)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${replies.size + 1}", style = Type.title, color = t.inkSoft, modifier = Modifier.size(32.dp))
                Text("Check bKash's summary, then enter your PIN", style = Type.title, color = t.ink)
            }
        }
        PrimaryButton("Open bKash menu", modifier = Modifier.padding(20.dp)) {
            if (Dialer.hasPermissions(context)) context.startActivity(Dialer.callIntent(context, simId))
            else context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "*247#", null)))
        }
    }
}

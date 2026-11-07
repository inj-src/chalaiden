package com.chalaiden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.R
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.QrStand
import com.chalaiden.app.ui.components.RoundAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.Sim

@Composable
fun HomeScreen(
    sims: List<Sim>,
    simId: Int?,
    onSelectSim: (Int) -> Unit,
    setupDone: Boolean,
    onPay: () -> Unit,
    onSend: () -> Unit,
    onCashOut: () -> Unit,
    onBalance: () -> Unit,
    onSetup: () -> Unit,
) {
    val t = tokens
    Column(
        Modifier.fillMaxSize().background(t.bg).systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // The brand is the header: the launcher mark over the wordmark, large.
        Spacer(Modifier.height(48.dp))
        Image(
            painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.height(64.dp).aspectRatio(42f / 36f),
        )
        Spacer(Modifier.height(18.dp))
        Text("chalaiden", style = Type.wordmark.copy(fontSize = 56.sp, letterSpacing = (-2).sp), color = t.ink)

        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Pay opens the scanner, which can switch to typing the number.
            RoundAction(AppIcons.Scan, "Pay", primary = true, modifier = Modifier.weight(1f), onClick = onPay)
            RoundAction(AppIcons.Send, "Send", modifier = Modifier.weight(1f), onClick = onSend)
            RoundAction(AppIcons.CashOut, "Cash out", modifier = Modifier.weight(1f), onClick = onCashOut)
            RoundAction(AppIcons.Wallet, "Balance", modifier = Modifier.weight(1f), onClick = onBalance)
        }

        Spacer(Modifier.height(40.dp))
        if (!setupDone) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onSetup)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(t.accent))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Finish setup", style = Type.title, color = t.ink)
                    Text("So Chalaiden can answer bKash for you", style = Type.label, color = t.inkSoft)
                }
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(28.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            QrStand()
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("What to scan", style = Type.title, color = t.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Aim at the number printed under the shop's bKash QR, not the QR itself. Chalaiden reads it and dials *247# for you.",
                    style = Type.label, color = t.inkSoft,
                )
            }
        }

        // One SIM needs no choosing, so nothing is shown. With more, a pill under the scan guide shows
        // which one bKash is dialled on; a tap moves to the next.
        if (sims.size > 1) {
            Spacer(Modifier.height(28.dp))
            SimPill(sims, simId, onSelectSim)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SimPill(sims: List<Sim>, simId: Int?, onSelect: (Int) -> Unit) {
    val t = tokens
    val current = sims.firstOrNull { it.subscriptionId == simId }
    val next = sims[(sims.indexOf(current) + 1).mod(sims.size)]
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50)).background(t.surface)
            .clickable(role = Role.Button, onClickLabel = "Use ${next.carrier}, SIM ${next.slot + 1}") { onSelect(next.subscriptionId) }
            .padding(start = 18.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Sim, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(280)) { it / 2 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(280)) { -it / 2 })
            },
            label = "sim",
            modifier = Modifier.weight(1f),
        ) { sim ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sim?.let { "SIM ${it.slot + 1}" } ?: "Choose a SIM", style = Type.button, color = t.ink)
                sim?.let {
                    Text(" · ${it.carrier}", style = Type.body, color = t.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("Change", style = Type.label, color = t.accent)
    }
}

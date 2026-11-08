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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.chalaiden.app.ui.components.capScale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chalaiden.app.R
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.SimMark
import com.chalaiden.app.ui.components.QrStand
import com.chalaiden.app.ui.components.ActionTile
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
    keyboardOff: Boolean = false,
    onFixKeyboard: () -> Unit = {},
    /** A payment has gone through before: the scan lesson folds into a link. */
    scanLearned: Boolean = false,
) {
    val t = tokens
    // Two halves: the brand (and anything needing attention) at the top; the actions low, in thumb reach,
    // where they are used at a shop counter. Scrolls only when text is very large.
    BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        val screen = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = screen).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    HomeMenu(onSetup, Modifier.align(Alignment.CenterEnd).offset(x = 12.dp))
                }
                Image(
                    painterResource(R.drawable.logo_mark),
                    contentDescription = null,
                    modifier = Modifier.height(40.dp).aspectRatio(42f / 36f),
                )
                Spacer(Modifier.height(12.dp))
                Text("chalaiden", style = Type.wordmark.copy(fontSize = 30.sp.capScale(), letterSpacing = (-1.0).sp), color = t.ink)
                Spacer(Modifier.height(24.dp))
                if (keyboardOff) {
                    // A session was cut off before it could switch the keyboards back on, and no root or Shizuku
                    // is here to do it now; the user can, in Settings.
                    Callout(t.error, "Your keyboard is off", "Turn it back on in Settings, or start Shizuku and reopen Chalaiden", onFixKeyboard)
                }
                if (!setupDone) Callout(t.accent, "Finish setup", "So Chalaiden can answer bKash for you", onSetup)
                // Reading matter up here, under the brand; the bottom is for the thumb.
                ScanLesson(folded = scanLearned)
            }

            Column {
                Spacer(Modifier.height(24.dp))
                // One SIM needs no choosing, so nothing is shown. With more, a pill shows which one bKash is
                // dialled on; a tap moves to the next.
                if (sims.size > 1) {
                    SimPill(sims, simId, onSelectSim)
                    Spacer(Modifier.height(24.dp))
                }
                // Four large blocks, two by two, in thumb reach. Pay opens the scanner, which can switch to typing the number.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(AppIcons.Scan, "Pay", primary = true, modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onPay)
                        ActionTile(AppIcons.Send, "Send", modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onSend)
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(AppIcons.CashOut, "Cash out", modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onCashOut)
                        ActionTile(AppIcons.Taka, "Balance", modifier = Modifier.weight(1f).fillMaxHeight(), onClick = onBalance)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A line that needs the user: a coloured dot, what's wrong, and where a tap takes them. */
@Composable
private fun Callout(dot: androidx.compose.ui.graphics.Color, title: String, detail: String, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = t.ink)
            Text(detail, style = Type.label, color = t.inkSoft)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(20.dp))
    }
}

/**
 * How to aim the scanner: the stand and a line on what to point at. A first-time lesson, so once a payment
 * has gone through it folds into a "What to scan?" link that opens it again.
 */
@Composable
private fun ScanLesson(folded: Boolean) {
    val t = tokens
    var open by rememberSaveable { mutableStateOf(!folded) }
    if (!open) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { open = true }.heightIn(min = 48.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What to scan?", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.ink)
        }
        return
    }
    // Side by side normally; stacked once the text can't keep a readable measure beside the stand.
    BoxWithConstraints {
        val stacked = LocalDensity.current.fontScale > 1.3f || maxWidth < 300.dp
        val words = @Composable { m: Modifier ->
            Column(m) {
                Text("What to scan", style = Type.title, color = t.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Aim at the number printed under the shop's bKash QR, not the QR itself. Chalaiden reads it and dials *247# for you.",
                    style = Type.label, color = t.inkSoft,
                )
            }
        }
        if (stacked) {
            Column {
                QrStand()
                Spacer(Modifier.height(20.dp))
                words(Modifier)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrStand()
                Spacer(Modifier.width(20.dp))
                words(Modifier.weight(1f))
            }
        }
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
        SimMark(current, fallbackTint = t.inkSoft, modifier = Modifier.size(22.dp))
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
        Text("Change", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.ink)
    }
}

/** The ⋮ button in Home's top-right corner and its menu. Setup is always one tap away from here. */
@Composable
private fun HomeMenu(onSetup: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Menu") { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.More, contentDescription = "Menu", tint = t.ink, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = if (t.isDark) t.surface else t.bg,
            shadowElevation = 8.dp,
            border = if (t.isDark) BorderStroke(1.dp, t.line) else null,
            offset = DpOffset((-8).dp, 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Change method", style = Type.body, color = t.ink) },
                onClick = { open = false; onSetup() },
                modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 48.dp),
            )
        }
    }
}

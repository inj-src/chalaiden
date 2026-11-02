package com.chalaiden.app.ui

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.QrStand
import com.chalaiden.app.ui.components.RoundAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens

@Composable
fun HomeScreen(
    simLabel: String?,
    setupDone: Boolean,
    onScan: () -> Unit,
    onType: () -> Unit,
    onSend: () -> Unit,
    onBalance: () -> Unit,
    onSetup: () -> Unit,
) {
    val t = tokens
    Column(
        Modifier.fillMaxSize().background(t.bg).systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("chalaiden", style = Type.wordmark, color = t.ink)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(t.raised)
                    .clickable(role = Role.Button, onClickLabel = "SIM and setup", onClick = onSetup),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Sim, contentDescription = "SIM and setup", tint = t.ink, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(36.dp))
        Text(simLabel?.let { "bKash on $it" } ?: "bKash", style = Type.label, color = t.inkSoft)
        Spacer(Modifier.height(6.dp))
        Text("Pay any shop,\nno internet needed.", style = Type.display.copy(fontSize = 34.sp, lineHeight = 40.sp), color = t.ink)

        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundAction(AppIcons.Scan, "Scan", primary = true, modifier = Modifier.weight(1f), onClick = onScan)
            RoundAction(AppIcons.Dialpad, "Type", modifier = Modifier.weight(1f), onClick = onType)
            RoundAction(AppIcons.Send, "Send", modifier = Modifier.weight(1f), onClick = onSend)
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
        Spacer(Modifier.height(24.dp))
    }
}

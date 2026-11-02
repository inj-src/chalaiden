package com.chalaiden.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens

@Composable
fun HomeScreen(
    simLabel: String?,
    setupDone: Boolean,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onBalance: () -> Unit,
    onSetup: () -> Unit,
) {
    val t = tokens
    Column(Modifier.fillMaxSize().background(t.paper).systemBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("chalaiden", style = Type.wordmark, color = t.ink)
            Spacer(Modifier.weight(1f))
            if (simLabel != null) {
                Text(simLabel, style = Type.label, color = t.inkSoft,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSetup).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        ScanBlock(onScan, Modifier.weight(1f))

        Spacer(Modifier.height(12.dp))
        Row("Send money", "To any bKash number", onSend)
        HorizontalDivider(color = t.line)
        Row("Check balance", "Asks bKash, needs your PIN", onBalance)

        if (!setupDone) {
            HorizontalDivider(color = t.line)
            Row("Finish setup", "So Chalaiden can answer bKash for you", onSetup, accent = true)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The one big thing on the home screen: aim the camera at the shop's card. */
@Composable
private fun ScanBlock(onScan: () -> Unit, modifier: Modifier) {
    val t = tokens
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.teal)
            .clickable(role = Role.Button, onClickLabel = "Scan to pay", onClick = onScan)
            .padding(24.dp),
    ) {
        Canvas(Modifier.align(Alignment.Center).fillMaxWidth(0.62f).height(120.dp)) {
            val len = 34.dp.toPx()
            val w = 5.dp.toPx()
            listOf(
                Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f),
            ).forEach { (o, d) ->
                drawLine(t.marigold, o, Offset(o.x + d.x * len, o.y), w, StrokeCap.Round)
                drawLine(t.marigold, o, Offset(o.x, o.y + d.y * len), w, StrokeCap.Round)
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text("Scan to pay", style = Type.display.copy(fontSize = 40.sp, lineHeight = 44.sp), color = t.onTeal)
            Spacer(Modifier.height(6.dp))
            Text("Point at the number on the shop's bKash card. Works without internet.",
                style = Type.body, color = t.onTeal.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun Row(title: String, hint: String, onClick: () -> Unit, accent: Boolean = false) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(t.marigold))
        if (accent) Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = t.ink)
            Text(hint, style = Type.label, color = t.inkSoft)
        }
        Text("›", style = Type.title.copy(fontSize = 28.sp), color = t.inkSoft)
    }
}

package com.chalaiden.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chalaiden.app.BuildConfig
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens

/** Where the project lives; the About screen links to it. */
private const val SOURCE_URL = "https://github.com/inj-src/chalaiden"

/** What Chalaiden is and does, from Home's ⋮ menu, with a link to its source code. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar("About", onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Chalaiden", style = Type.display, color = t.ink)
            Spacer(Modifier.height(4.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", style = Type.label, color = t.inkSoft)
            Spacer(Modifier.height(20.dp))
            Text(
                "Pay a shop, send money, cash out or check your balance with bKash when there's no internet.",
                style = Type.title, color = t.ink,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Chalaiden goes through bKash's *247# menu for you and shows you bKash's answer. " +
                    "*247# works over your mobile signal, so you don't need any mobile data.",
                style = Type.body, color = t.inkSoft,
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Fact(AppIcons.Lock, "Your PIN goes only to bKash. Chalaiden saves it only if you turn on One Tap, and then locked to your fingerprint.")
                Fact(AppIcons.Scan, "A shop's number is read from its card on your phone. Nothing is uploaded.")
                Fact(AppIcons.Alert, "Chalaiden isn't made by bKash or connected to it.")
            }
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(t.surface)
                    .clickable(role = Role.Button, onClickLabel = "Open in browser") {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Source code", style = Type.body, color = t.ink)
                    Text(SOURCE_URL.removePrefix("https://"), style = Type.label, color = t.accent)
                }
                Icon(AppIcons.Send, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    val t = tokens
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = t.inkSoft, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.body, color = t.ink)
    }
}

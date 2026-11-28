package com.chalaiden.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.BuildConfig
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.Spinner
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.update.Release
import com.chalaiden.app.update.Updates
import kotlinx.coroutines.launch

/** Opens the new version's APK (or its release page) in the browser, which downloads it. */
internal fun download(context: Context, release: Release) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl ?: release.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** GitHub release notes are Markdown; shown as plain lines, without the marks. */
private fun plain(notes: String) = notes.lines()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .joinToString("\n") { line ->
        val bullet = line.startsWith("- ") || line.startsWith("* ")
        val text = line.trimStart('#', '*', '-', ' ').replace("**", "").replace("`", "")
        if (bullet) "• $text" else text
    }

/** Over Home when a newer version is out. "Not now", or swiping it away, hides it for a week. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(release: Release, onDismiss: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { then() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = t.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 8.dp)) {
            Text("A new version is out", style = Type.display.copy(fontSize = 26.sp, lineHeight = 31.sp), color = t.ink)
            Spacer(Modifier.height(8.dp))
            Text("Chalaiden ${release.version} is ready. You have ${BuildConfig.VERSION_NAME}.", style = Type.body, color = t.inkSoft)
            plain(release.notes).takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it, style = Type.label.copy(lineHeight = 20.sp), color = t.ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.raised).padding(14.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Download") { download(context, release); close(onDismiss) }
            TextAction("Not now", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), color = t.inkSoft) { close(onDismiss) }
        }
    }
}

/** From Home's ⋮ menu: this version, the latest one, and a way to check again. */
@Composable
fun UpdatesScreen(onBack: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updates.check(context) }
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar("Updates", onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Chalaiden ${BuildConfig.VERSION_NAME}", style = Type.display, color = t.ink)
            Spacer(Modifier.height(4.dp))
            Text("The version on this phone", style = Type.label, color = t.inkSoft)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.surface).padding(16.dp)) {
                when (val s = state) {
                    Updates.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(t.ink, 18.dp, stroke = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking GitHub…", style = Type.body, color = t.ink)
                    }
                    is Updates.State.Available -> {
                        Text("Version ${s.release.version} is out", style = Type.title, color = t.ink)
                        plain(s.release.notes).takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = Type.label.copy(lineHeight = 20.sp), color = t.inkSoft)
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("Download") { download(context, s.release) }
                    }
                    Updates.State.UpToDate -> {
                        Text("You're up to date", style = Type.title, color = t.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(checked(context), style = Type.label, color = t.inkSoft)
                    }
                    Updates.State.Failed -> {
                        Text("Couldn't check", style = Type.title, color = t.ink)
                        Spacer(Modifier.height(4.dp))
                        Text("Connect to the internet and try again.", style = Type.label, color = t.inkSoft)
                    }
                    Updates.State.Unknown -> Text("Not checked yet", style = Type.title, color = t.ink)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "When you're online, Chalaiden asks GitHub for its latest version now and then. Nothing else is sent.",
                style = Type.label.copy(lineHeight = 20.sp), color = t.inkSoft,
            )
        }
        TextAction(
            "Check again",
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            enabled = state != Updates.State.Checking,
        ) { scope.launch { Updates.check(context, force = true) } }
    }
}

private fun checked(context: Context): String {
    val at = Updates.checkedAt(context)
    if (at == 0L) return ""
    if (System.currentTimeMillis() - at < DateUtils.MINUTE_IN_MILLIS) return "Checked just now"
    return "Checked " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

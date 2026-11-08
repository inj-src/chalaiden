package com.chalaiden.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.GuideSession

/**
 * The step in front of the user, kept to one line above bKash's dialog: which step, Next or Done, and
 * close. The Chalaiden keyboard types the replies, so the value only shows when it isn't the keyboard
 * in use, and is then copied as its step comes up, for a long-press paste. [floating]: drawn in [GuideCover].
 */
@Composable
fun GuideCard(guide: GuideSession.State, floating: Boolean, onDone: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val step = guide.step
    val typing = remember(guide.index, guide.filled) { ChalaidenKeyboard.isCurrent(context) }
    // Android shows its "Copied" preview on every copy and offers apps no way to hide it, so copy only
    // when someone has to paste: never while the Chalaiden keyboard is typing the replies.
    LaunchedEffect(guide.index, guide.flow) {
        if (!ChalaidenKeyboard.isCurrent(context)) step.value?.let { copy(context, it) }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (floating) 8.dp else 0.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(t.surface)
                .border(1.dp, t.line, RoundedCornerShape(50)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${guide.index + 1}/${guide.steps.size}", style = Type.label, color = t.accent)
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                targetState = guide.index,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "guide-step",
                modifier = Modifier.weight(1f),
            ) { index ->
                val s = guide.steps[index]
                Column {
                    Text(s.title, style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (!typing && s.value != null) Text(s.value, style = Type.label, color = t.inkSoft, maxLines = 1)
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(t.accent)
                    .clickable(role = Role.Button) { if (guide.isLast) onDone() else GuideSession.next() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (guide.isLast) "Done" else "Next", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.onAccent)
            }
            CardIcon(AppIcons.Cross, "Close the guide") { GuideSession.end(GuideSession.End.Closed) }
        }
    }
}

@Composable
private fun CardIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val t = tokens
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = t.inkSoft, modifier = Modifier.size(18.dp))
    }
}

/** Puts a reply on the clipboard. Not marked sensitive: it is a number or an amount, never the PIN. */
private fun copy(context: Context, text: String): Boolean = runCatching {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("bKash reply", text))
    true
}.getOrDefault(false)

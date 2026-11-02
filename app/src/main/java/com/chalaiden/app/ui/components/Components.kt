package com.chalaiden.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import kotlinx.coroutines.launch

/** Bangladeshi mobile number grouping used on merchant cards: 017 4560 6306. */
val PhoneGroups = listOf(3, 4, 4)

/**
 * A phone number on one rail: equal cells in 3-4-4 groups, a faint tick between groups
 * (a quiet nod to the boxed digits on merchant cards). Empty cells show a dot so you can
 * see what's missing; with [cursor] the next cell gets a marigold caret.
 *
 * [compact] drops the container for headers and summaries. [onTeal] is for teal surfaces.
 */
@Composable
fun NumberRail(
    digits: String,
    modifier: Modifier = Modifier,
    groups: List<Int> = PhoneGroups,
    cursor: Boolean = false,
    compact: Boolean = false,
    onTeal: Boolean = false,
) {
    val t = tokens
    val ink = if (onTeal) t.onTeal else t.ink
    val faint = if (onTeal) t.onTeal.copy(alpha = 0.35f) else t.line
    val fontSize = if (compact) 22.sp else 32.sp
    val cellWidth = if (compact) 15.dp else 0.dp // 0 = share the full width
    val groupGap = if (compact) 14.dp else 18.dp
    val description = digits.chunked(1).joinToString(" ")

    val rail = @Composable {
        Row(
            Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var index = 0
            groups.forEachIndexed { g, size ->
                if (g > 0) {
                    Box(Modifier.width(groupGap), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(1.dp).height(if (compact) 12.dp else 18.dp).background(faint))
                    }
                }
                repeat(size) {
                    val ch = digits.getOrNull(index)
                    val caret = cursor && index == digits.length
                    Box(
                        (if (cellWidth > 0.dp) Modifier.width(cellWidth) else Modifier.weight(1f))
                            .height(if (compact) 30.dp else 44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            ch != null -> Text(ch.toString(), style = Type.digit.copy(fontSize = fontSize), color = ink)
                            caret -> Box(Modifier.width(2.dp).height(if (compact) 18.dp else 28.dp).clip(RoundedCornerShape(1.dp)).background(t.marigold))
                            else -> Box(Modifier.size(if (compact) 4.dp else 5.dp).clip(CircleShape).background(faint))
                        }
                    }
                    index++
                }
            }
        }
    }

    if (compact) {
        Box(modifier) { rail() }
    } else {
        Box(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (onTeal) t.onTeal.copy(alpha = 0.1f) else t.tile)
                .border(1.dp, if (onTeal) Color.Transparent else t.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) { rail() }
    }
}

/** Large on-screen keypad. [extra] fills the bottom-left key (e.g. "." or nothing). */
@Composable
fun Keypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    extra: Char? = null,
    dark: Boolean = false,
) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val keyColor = if (dark) t.onTeal else t.ink
    val rows = listOf("123", "456", "789")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c -> Key(c.toString(), keyColor, Modifier.weight(1f)) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(c) } }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            if (extra != null) Key(extra.toString(), keyColor, Modifier.weight(1f)) { onKey(extra) } else Spacer(Modifier.weight(1f))
            Key("0", keyColor, Modifier.weight(1f)) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey('0') }
            Key("⌫", keyColor, Modifier.weight(1f), description = "Delete") { onBackspace() }
        }
    }
}

@Composable
private fun Key(label: String, color: Color, modifier: Modifier, description: String? = null, onClick: () -> Unit) {
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.digit.copy(fontSize = 26.sp), color = color)
    }
}

@Composable
fun PinDots(filled: Int, length: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(length) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (i < filled) color else Color.Transparent)
                    .border(2.dp, color, CircleShape),
            )
        }
    }
}

/**
 * Press and hold to confirm a payment. A plain tap is too easy to make by accident
 * for something that moves money.
 */
@Composable
fun HoldToConfirm(label: String, enabled: Boolean, modifier: Modifier = Modifier, onConfirmed: () -> Unit) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) t.marigold.copy(alpha = 0.35f) else t.line)
            .semantics { contentDescription = "$label. Press and hold." }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.animateTo(1f, tween(900, easing = LinearEasing))
                        if (!done) {
                            done = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirmed()
                        }
                    }
                    waitForUpOrCancellation()
                    if (!done) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(if (enabled) t.marigold else t.line))
        Text(
            if (progress.value > 0f && !done) "Keep holding…" else label,
            style = Type.button,
            color = if (enabled) t.onMarigold else t.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) t.teal else t.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.button, color = if (enabled) t.onTeal else t.inkSoft)
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = t.ink)
        }
        Text(title, style = Type.title, color = t.ink)
    }
}

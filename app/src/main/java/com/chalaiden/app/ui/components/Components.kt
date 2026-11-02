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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import kotlinx.coroutines.launch

/** Bangladeshi mobile number grouping used on merchant cards: 017 4560 6306. */
val PhoneGroups = listOf(3, 4, 4)

/**
 * A phone number on one rail: equal cells in 3-4-4 groups with a faint tick between groups
 * (a quiet nod to the boxed digits on merchant cards). Empty cells show a dot; with [cursor]
 * the next cell gets a marigold caret. [compact] drops the container for headers and summaries.
 */
@Composable
fun NumberRail(
    digits: String,
    modifier: Modifier = Modifier,
    groups: List<Int> = PhoneGroups,
    cursor: Boolean = false,
    compact: Boolean = false,
) {
    val t = tokens
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
                        Box(Modifier.width(1.dp).height(if (compact) 12.dp else 18.dp).background(t.line))
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
                            ch != null -> Text(ch.toString(), style = Type.digit.copy(fontSize = fontSize), color = t.ink)
                            caret -> Box(Modifier.width(2.dp).height(if (compact) 18.dp else 28.dp).clip(RoundedCornerShape(1.dp)).background(t.accent))
                            else -> Box(Modifier.size(if (compact) 4.dp else 5.dp).clip(CircleShape).background(t.line))
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
        Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.surface).padding(16.dp)) { rail() }
    }
}

/** Round icon button with a label under it. [primary] is white, the rest are graphite. */
@Composable
fun RoundAction(icon: ImageVector, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(if (primary) t.ink else t.raised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (primary) t.bg else t.ink, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = Type.label, color = t.ink, textAlign = TextAlign.Center)
    }
}

/** Small pill with an icon, for secondary ways in (contacts, paste, scan). */
@Composable
fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(t.raised).clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.label, color = t.ink)
    }
}

/** Large on-screen keypad. */
@Composable
fun Keypad(onKey: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val tap = { c: Char -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(c) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { tap(c) } }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Key("0", Modifier.weight(1f)) { tap('0') }
            Key("", Modifier.weight(1f), description = "Delete", icon = AppIcons.Backspace) { onBackspace() }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, description: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val t = tokens
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(26.dp))
        else Text(label, style = Type.digit.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal), color = t.ink)
    }
}

@Composable
fun PinDots(filled: Int, length: Int, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(length) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (i < filled) t.accent else Color.Transparent)
                    .border(2.dp, if (i < filled) t.accent else t.line, CircleShape),
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
            .height(60.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) t.accent.copy(alpha = 0.28f) else t.raised)
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
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(if (enabled) t.accent else t.raised))
        Text(
            if (progress.value > 0f && !done) "Keep holding…" else label,
            style = Type.button,
            color = if (enabled) t.onAccent else t.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** The main action on a screen: a white pill. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) t.ink else t.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.button, color = if (enabled) t.bg else t.inkSoft)
    }
}

/** A quieter action under the primary one. */
@Composable
fun TextAction(label: String, modifier: Modifier = Modifier, color: Color = tokens.ink, onClick: () -> Unit) {
    Text(
        label,
        style = Type.button,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** Back button in a graphite circle, then the screen title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(t.raised).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Back, contentDescription = "Back", tint = t.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = Type.title, color = t.ink)
    }
}

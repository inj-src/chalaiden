package com.chalaiden.app.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
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
    val groupGap = 18.dp
    val description = digits.chunked(1).joinToString(" ")

    // Digits grow with the user's font size until the rail fills its row, then stop: an 11-digit number
    // must stay on one line and whole, at 200% text on a 320dp phone too.
    val rail = @Composable {
        BoxWithConstraints(Modifier.semantics { contentDescription = description }) {
            val density = LocalDensity.current
            val cells = groups.sum()
            val fitCell = (maxWidth - groupGap * (groups.size - 1)) / cells
            val wanted = with(density) { 32.sp.toDp() }
            val font = minOf(wanted, fitCell * 1.45f)
            val cellWidth = fitCell
            val cellHeight = font * 1.38f
            val fontSize = with(density) { font.toSp() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var index = 0
                groups.forEachIndexed { g, size ->
                    if (g > 0) {
                        Box(Modifier.width(groupGap), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(1.dp).height(font * 0.56f).background(t.line))
                        }
                    }
                    repeat(size) {
                        val ch = digits.getOrNull(index)
                        val caret = cursor && index == digits.length
                        Box(Modifier.width(cellWidth).height(cellHeight), contentAlignment = Alignment.Center) {
                            when {
                                ch != null -> Text(ch.toString(), style = Type.digit.copy(fontSize = fontSize), color = t.ink, maxLines = 1, softWrap = false)
                                caret -> Box(Modifier.width(2.dp).height(font * 0.85f).clip(RoundedCornerShape(1.dp)).background(t.accent))
                                else -> Box(Modifier.size(5.dp).clip(CircleShape).background(t.line))
                            }
                        }
                        index++
                    }
                }
            }
        }
    }

    if (compact) {
        // Read, not typed into: plain figures in the app's text face, groups split by a little air.
        // The cells and ticks belong to the entry rail, where they stand for slots still to fill.
        BasicText(
            buildAnnotatedString {
                var at = 0
                groups.forEachIndexed { g, n ->
                    if (g > 0) withStyle(SpanStyle(letterSpacing = 0.sp)) { append("\u2002") }
                    append(digits.substring(at.coerceAtMost(digits.length), (at + n).coerceAtMost(digits.length)))
                    at += n
                }
                if (at < digits.length) append(digits.substring(at))
            },
            modifier = modifier.semantics { contentDescription = description },
            style = Type.digit.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, color = t.ink),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
        )
    } else {
        Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.surface).padding(16.dp)) { rail() }
    }
}

/** The ease-out curve for press feedback: quick to answer the finger, soft to settle. */
private val PressEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Press feedback: shrinks to [pressed] while the finger is down, and back on release. Not for the
 * keypad: keys are typed fast, like a keyboard, and motion there only gets in the way.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) pressed else 1f, tween(if (down) 120 else 200, easing = PressEase), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * One of Home's four main actions, as a large block in a 2×2 grid: the icon at the top, the label at the
 * bottom, both left-aligned so the four read as a set. [primary] (Pay) is filled with ink.
 */
@Composable
fun ActionTile(icon: ImageVector, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    val press = remember { MutableInteractionSource() }
    val ink = if (primary) t.bg else t.ink
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.heightIn(min = 112.dp).pressScale(press, 0.97f).clip(shape).background(if (primary) t.ink else t.surface)
            .clickable(interactionSource = press, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        BasicText(
            label,
            style = Type.title.copy(color = ink),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = Type.title.fontSize, stepSize = 0.5.sp),
        )
    }
}

/** Small pill with an icon, for secondary ways in (contacts, paste, scan). */
@Composable
fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = tokens
    val press = remember { MutableInteractionSource() }
    // 44dp to see, 48dp to hit: the touch target reaches past the pill.
    Row(
        Modifier.minimumInteractiveComponentSize().pressScale(press).heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(t.raised)
            .clickable(interactionSource = press, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.label, color = t.ink)
    }
}

/**
 * Large on-screen keypad. Given a bounded height (e.g. `Modifier.weight(1f)`), its keys shrink from 64dp
 * to 40dp to fit, sitting at the bottom of that space; otherwise they are [keyHeight] tall.
 */
@Composable
fun Keypad(onKey: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier, keyHeight: Dp? = null) {
    val haptics = LocalHapticFeedback.current
    val tap = { c: Char -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(c) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val h = keyHeight ?: if (maxHeight == Dp.Infinity) 64.dp else ((maxHeight - 24.dp) / 4).coerceIn(40.dp, 64.dp)
        // Gaps between keys, so two quick presses light two separate keys instead of one slab.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> Key(c.toString(), Modifier.weight(1f), h) { tap(c) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Key("0", Modifier.weight(1f), h) { tap('0') }
                Key("", Modifier.weight(1f), h, description = "Delete", icon = AppIcons.Backspace) { onBackspace() }
            }
        }
    }
}

/**
 * [this] size, growing with the user's text size only up to [max]. For type that is already very large
 * (amounts, the wordmark), where 200% would push everything else off screen.
 */
@Composable
fun TextUnit.capScale(max: Float = 1.3f): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale > max) this * (max / scale) else this
}

/** Drawings keep their proportions: text inside them is part of the picture, not something to read. */
@Composable
fun FixedTextScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1f), content = content)
}

@Composable
private fun Key(label: String, modifier: Modifier, height: Dp, description: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val t = tokens
    // The digit follows the user's text size, but never outgrows a key squeezed to fit a short screen.
    val digitSize = with(LocalDensity.current) { minOf(28.sp.toDp(), height * 0.52f).toSp() }
    // Our own press state instead of a ripple: lights at once, lets go quickly, so a fast PIN never
    // leaves a trail of half-faded keys behind it.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) t.raised else t.raised.copy(alpha = 0f),
        animationSpec = tween(if (pressed) 120 else 180),
        label = "key",
    )
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description }
            .background(fill, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(26.dp))
        else Text(label, style = Type.digit.copy(fontSize = digitSize, fontWeight = FontWeight.Normal), color = t.ink)
    }
}

@Composable
fun PinDots(filled: Int, length: Int, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(length) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
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
fun HoldToConfirm(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** The finger went down; work that should start early (warming up the bKash menu) starts here. */
    onHoldStart: () -> Unit = {},
    /** Let go before the hold completed; undo whatever [onHoldStart] began. */
    onHoldCancel: () -> Unit = {},
    onConfirmed: () -> Unit,
) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    // The gesture below outlives recompositions; it must see the button as it is now.
    val enabledNow by rememberUpdatedState(enabled)
    val confirmedNow by rememberUpdatedState(onConfirmed)
    // Ready: a solid accent pill, the one blue button in the app. Not ready: the same grey as a disabled
    // primary button. The hold sweeps a deeper blue across it, left to right.
    val track by animateColorAsState(if (enabled) t.accent else t.raised, tween(180), label = "track")
    val ink by animateColorAsState(if (enabled) t.onAccent else t.inkSoft, tween(180), label = "ink")
    val sweep = lerp(t.accent, Color.Black, 0.28f)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .semantics { contentDescription = "$label. Press and hold." }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (!done) onHoldStart()
                    val job = scope.launch {
                        progress.animateTo(1f, tween(900, easing = LinearEasing))
                        if (!done && enabledNow) {
                            done = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmedNow()
                        }
                    }
                    // Also runs when the button is disabled mid-hold (e.g. a PIN digit deleted with a
                    // second finger): that restarts this gesture, and the fill must stop with it.
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        if (!done) {
                            job.cancel()
                            onHoldCancel()
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(sweep))
        Text(
            if (progress.value > 0f && !done) "Keep holding…" else label,
            style = Type.button,
            color = ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** The main action on a screen: a white pill. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    val press = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (enabled) t.ink else t.raised, tween(180), label = "fill")
    val text by animateColorAsState(if (enabled) t.bg else t.inkSoft, tween(180), label = "text")
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(interactionSource = press, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.button, color = text)
    }
}

/** A quieter action under the primary one. */
@Composable
fun TextAction(label: String, modifier: Modifier = Modifier, color: Color = tokens.ink, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Text(
        label,
        style = Type.button,
        color = if (enabled) color else tokens.inkSoft,
        textAlign = TextAlign.Center,
        modifier = modifier.pressScale(press).widthIn(min = 120.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
            .clickable(interactionSource = press, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** Back button in a graphite circle, then the screen title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).pressScale(press, 0.94f).clip(CircleShape).background(t.raised)
                .clickable(interactionSource = press, indication = LocalIndication.current, role = Role.Button, onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Back, contentDescription = "Back", tint = t.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = Type.title, color = t.ink)
    }
}

/**
 * A label and its value, side by side when both fit on one line. When they don't (a 20-letter reference,
 * a long TrxID at large text), the value moves under its label whole, rather than breaking mid-token
 * ("BKH9X7Q2ZL / MW") or squeezing the label ("Referen / ce").
 */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val t = tokens
    // [strong]: the line that matters most (the total), in title size and both halves in ink.
    val valueStyle = if (strong) Type.title.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
        else Type.body.copy(fontFamily = Type.button.fontFamily, fontFeatureSettings = "tnum")
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Text(label, style = if (strong) Type.title else Type.body, color = if (strong) t.ink else t.inkSoft, maxLines = 1, softWrap = false)
            Text(withTakaAfter(value, t.inkSoft), style = valueStyle, color = t.ink)
        },
    ) { (labelM, valueM), c ->
        val gap = 16.dp.roundToPx()
        val l = labelM.measure(c.copy(minWidth = 0))
        val oneLine = valueM.maxIntrinsicWidth(c.maxHeight)
        if (l.width + gap + oneLine <= c.maxWidth) {
            val v = valueM.measure(c.copy(minWidth = 0))
            val h = maxOf(l.height, v.height)
            layout(c.maxWidth, h) {
                l.place(0, 0)
                v.place(c.maxWidth - v.width, 0)
            }
        } else {
            val v = valueM.measure(c.copy(minWidth = 0))
            layout(c.maxWidth, l.height + v.height) {
                l.place(0, 0)
                v.place(0, l.height)
            }
        }
    }
}

/** bKash writes money "Tk 9.25"; Chalaiden shows it as everywhere else, "9.25 TK". Display only. */
fun takaAfter(text: String): String = TakaBefore.replace(text) { "${it.groupValues[1]} TK" }

private val TakaBefore = Regex("""\bTk\.?\s*([\d,]+(?:\.\d+)?)""")

/** [takaAfter], with each "TK" set quieter than its number, as on the big amounts. */
fun withTakaAfter(text: String, soft: Color): AnnotatedString = buildAnnotatedString {
    val shown = takaAfter(text)
    var at = 0
    Regex("""(?<=\d) TK\b""").findAll(shown).forEach { m ->
        append(shown.substring(at, m.range.first))
        append(" ")
        withStyle(SpanStyle(fontSize = 0.8.em, color = soft)) { append("TK") }
        at = m.range.last + 1
    }
    append(shown.substring(at))
}

/**
 * An amount as the app sets it everywhere: the number, then a small soft "TK", on one baseline (as on the
 * amount screen and the receipt). Steps the size down rather than wrapping "TK" under the number.
 * [amount] is the number alone ("1,250"); [color] dims it where the money may not have moved.
 */
@Composable
fun AmountText(amount: String, modifier: Modifier = Modifier, color: Color = tokens.ink) {
    val t = tokens
    val text = buildAnnotatedString {
        append(amount)
        append("\u2009")
        withStyle(SpanStyle(fontSize = 0.42.em, color = t.inkSoft, letterSpacing = 0.sp)) { append("TK") }
    }
    BasicText(
        text,
        modifier = modifier.fillMaxWidth(),
        style = Type.amount.copy(color = color),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = Type.amount.fontSize, stepSize = 2.sp),
    )
}

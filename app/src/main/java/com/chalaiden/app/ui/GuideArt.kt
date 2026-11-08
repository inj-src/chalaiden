package com.chalaiden.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.R
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.Spinner
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens

/*
 * The looping pictures in the set-up guides ([MethodGuideScreen]): a small phone that acts out one step.
 * Each picture runs on one clock, `c`, going 0 → 1 and starting over; every part works out where it is
 * from `c`, so a whole scene is a few lines of timings. A finger dot with a ripple shows each tap.
 */

private val Tiny = Type.label.copy(fontSize = 9.5.sp, lineHeight = 12.5.sp)
private val Small = Type.label.copy(fontSize = 11.sp, lineHeight = 14.sp)
private val SmallBold = Small.copy(fontWeight = FontWeight.SemiBold)

/** Springy ease that overshoots a little, for things that pop in. */
private val Pop = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** 0 before [a], 1 after [b], linear in between. */
private fun Float.span(a: Float, b: Float) = ((this - a) / (b - a)).coerceIn(0f, 1f)
private fun Float.ease(a: Float, b: Float) = FastOutSlowInEasing.transform(span(a, b))
private fun Float.pop(a: Float, b: Float) = Pop.transform(span(a, b))

/**
 * A phone in a card, its content running on a [millis]-long loop. The content fades in at the start of
 * each loop and out at the end, so the restart never jumps.
 */
@Composable
private fun Art(millis: Int, content: @Composable ColumnScope.(c: Float) -> Unit) {
    val t = tokens
    val clock = rememberInfiniteTransition(label = "guide-art")
    val c by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = LinearEasing)), label = "clock")
    val phone = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(t.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            // Pushed a little past the card's bottom edge, which crops it: the phone reads as cut off there.
            Modifier.offset(y = 8.dp).padding(top = 22.dp).width(192.dp).fillMaxHeight()
                .clip(phone).background(t.bg).border(1.5.dp, t.line, phone)
                .padding(horizontal = 12.dp).padding(top = 16.dp)
                .graphicsLayer { alpha = minOf(c.span(0f, 0.05f), 1f - c.span(0.94f, 1f)) },
        ) { content(c) }
    }
}

/**
 * A finger pressing the middle of this element at [at] (on the loop clock [c]): a dot that comes in,
 * presses for [hold], and lifts with a ripple. Put it before any `clip` so the ripple can spill over.
 */
private fun Modifier.tap(c: Float, at: Float, hold: Float = 0f, lift: Float = 0.12f): Modifier = composed {
    // The fingertip is the opposite of the canvas: white on dark, near-black on light.
    val finger = tokens.ink
    drawWithContent {
    drawContent()
    val lead = 0.05f
    val p = c - at
    if (p < -lead || p > hold + lift) return@drawWithContent
    val center = Offset(size.width / 2, size.height / 2)
    val r = 10.dp.toPx()
    val pressed = p in 0f..hold + 0.03f
    val lifted = ((p - hold) / lift).coerceIn(0f, 1f)
    val alpha = if (p < 0) (1f + p / lead) * 0.5f else 0.5f * (1f - lifted)
    drawCircle(finger.copy(alpha = alpha), r * if (pressed) 0.82f else 1f, center)
    if (p >= 0) {
        val ring = (p / (hold + lift)).coerceIn(0f, 1f)
        drawCircle(finger.copy(alpha = 0.45f * (1f - ring)), r * (1f + 1.5f * ring), center, style = Stroke(1.5.dp.toPx()))
    }
    }
}

// ---------------------------------------------------------------------------------------------------
// Shizuku

/** Getting Shizuku from the store: Install fills up, turns into Open, and the app gets a tick. */
@Composable
fun InstallArt(update: Boolean) = Art(4400) { c ->
    val t = tokens
    val progress = c.span(0.22f, 0.6f)
    val installing = progress > 0f && c < 0.62f
    val done = c >= 0.62f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            ShizukuIcon(40)
            if (done) {
                val s = c.pop(0.62f, 0.72f)
                CheckDot(Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).graphicsLayer { scaleX = s; scaleY = s })
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Shizuku", style = SmallBold, color = t.ink)
            Text("Free on Google Play", style = Tiny, color = t.inkSoft)
        }
    }
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().height(30.dp).tap(c, 0.16f).clip(RoundedCornerShape(50))
            .background(if (installing) t.raised else t.accent),
        contentAlignment = Alignment.Center,
    ) {
        if (installing) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(t.accent))
        Text(
            when {
                done -> "Open"
                installing -> "${if (update) "Updating" else "Installing"} ${(progress * 100).toInt()}%"
                else -> if (update) "Update" else "Install"
            },
            style = SmallBold, color = t.onAccent,
        )
    }
    Spacer(Modifier.height(16.dp))
    Bars(0.95f, 0.8f, 0.88f, 0.55f)
}

/** Settings › About phone: Build number tapped seven times, a countdown, then "You're now a developer". */
@Composable
fun DeveloperArt() = Art(5200) { c ->
    val t = tokens
    val first = 0.12f
    val gap = 0.06f
    val taps = (0..6).count { c >= first + it * gap }
    val current = ((c - first) / gap).toInt().coerceIn(0, 6)
    Box(Modifier.fillMaxSize()) {
        Column {
            Text("About phone", style = SmallBold, color = t.ink)
            Spacer(Modifier.height(6.dp))
            MockRow("Device name", "My phone")
            MockRow("Android version", "16")
            Row(
                Modifier.fillMaxWidth().tap(c, first + current * gap, lift = 0.05f).clip(RoundedCornerShape(8.dp))
                    .background(if (taps in 1..6) t.surface else Color.Transparent).padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Build number", style = Small, color = t.ink)
                    Text("16.0.1.300", style = Tiny, color = t.inkSoft)
                }
                if (taps in 1..6) Pill("${7 - taps} to go", t.raised, t.ink)
            }
        }
        val toast = c.ease(0.58f, 0.64f)
        if (toast > 0f) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    .graphicsLayer { alpha = toast; translationY = (1f - toast) * 16.dp.toPx() }
                    .clip(RoundedCornerShape(50)).background(t.raised).padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text("You're now a developer!", style = Tiny, color = t.ink) }
        }
    }
}

/** Wireless debugging shows a code; Shizuku's notification drops in and the code is typed into it. */
@Composable
fun PairArt() = Art(5800) { c ->
    val t = tokens
    val code = "482913"
    val typed = (c.span(0.32f, 0.6f) * code.length).toInt().coerceAtMost(code.length)
    val paired = c >= 0.68f
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 78.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).padding(12.dp),
        ) {
            Text("Pair with device", style = SmallBold, color = t.ink)
            Spacer(Modifier.height(6.dp))
            Text("Wi-Fi pairing code", style = Tiny, color = t.inkSoft)
            Text("482 913", style = SmallBold.copy(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 2.sp), color = t.ink)
            Spacer(Modifier.height(6.dp))
            Bars(0.7f)
        }
        val n = c.ease(0.08f, 0.18f)
        Column(
            Modifier.fillMaxWidth().graphicsLayer { translationY = -(1f - n) * 60.dp.toPx(); alpha = n }
                .clip(RoundedCornerShape(14.dp)).background(t.surface).border(1.dp, t.line, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShizukuIcon(12)
                Spacer(Modifier.width(5.dp))
                Text("Shizuku", style = Tiny, color = t.inkSoft)
            }
            Text(if (paired) "Paired" else "Pairing service found", style = Small, color = t.ink)
            Spacer(Modifier.height(6.dp))
            if (paired) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val s = c.pop(0.68f, 0.76f)
                    CheckDot(Modifier.graphicsLayer { scaleX = s; scaleY = s })
                    Spacer(Modifier.width(6.dp))
                    Text("Shizuku can start now", style = Tiny, color = t.inkSoft)
                }
            } else {
                Field(code.take(typed), focused = c >= 0.28f, modifier = Modifier.tap(c, 0.26f), hint = "Pairing code")
            }
        }
    }
}

/** Shizuku's Start button: a spinner, then the dot turns blue and says it's running. */
@Composable
fun StartArt() = Art(4600) { c ->
    val t = tokens
    val starting = c >= 0.28f && c < 0.54f
    val running = c >= 0.54f
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShizukuIcon(20)
        Spacer(Modifier.width(7.dp))
        Text("Shizuku", style = SmallBold, color = t.ink)
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.raised).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            if (starting) {
                Spinner(t.accent, 12.dp, stroke = 2.dp)
            } else {
                val s = if (running) c.pop(0.54f, 0.62f) else 1f
                Box(Modifier.size(9.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(if (running) t.accent else t.inkSoft))
            }
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(if (running) "Shizuku is running" else if (starting) "Starting…" else "Not running", style = Small, color = t.ink)
            Text(if (running) "Apps can use it now" else "Start it below", style = Tiny, color = t.inkSoft)
        }
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.surface).padding(12.dp)) {
        Text("Start via Wireless debugging", style = Small, color = t.ink)
        Spacer(Modifier.height(5.dp))
        Bars(0.85f)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Pairing", t.raised, t.ink)
            Pill("Start", t.accent, t.onAccent, Modifier.tap(c, 0.24f))
        }
    }
}

/** Shizuku's prompt slides up; "Allow all the time" is tapped; Chalaiden is allowed. */
@Composable
fun AllowShizukuArt() = PromptArt(
    title = "Allow Chalaiden to access Shizuku?",
    allow = "Allow all the time",
    deny = "Deny",
    result = "Chalaiden allowed",
    icon = { ShizukuIcon(28) },
)

// ---------------------------------------------------------------------------------------------------
// Onboarding

/** Android's phone-access prompt slides up over Chalaiden; Allow is tapped. */
@Composable
fun PhoneAccessArt() = PromptArt(
    title = "Allow Chalaiden to make and manage phone calls?",
    allow = "Allow",
    deny = "Don't allow",
    result = "Phone access allowed",
    icon = {
        Box(Modifier.size(28.dp).clip(CircleShape).background(tokens.accent), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Dialpad, null, tint = tokens.onAccent, modifier = Modifier.size(16.dp))
        }
    },
)

// ---------------------------------------------------------------------------------------------------
// Root

/**
 * The root managers Chalaiden works with, standing in an arch: KernelSU at the top, Magisk and APatch
 * either side. A still picture; root users know these apps and only need to see which ones count.
 */
@Composable
fun RootArt() {
    val t = tokens
    Box(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(t.surface),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            RootManager(R.drawable.root_magisk, "Magisk", lift = 0)
            RootManager(R.drawable.root_kernelsu, "KernelSU", lift = 34)
            RootManager(R.drawable.root_apatch, "APatch", lift = 0)
        }
    }
}

/** One root manager's icon with its name under it, raised [lift] dp above its neighbours. */
@Composable
private fun RootManager(icon: Int, name: String, lift: Int) {
    Column(
        Modifier.padding(bottom = lift.dp).width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(icon), null, Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)))
        Spacer(Modifier.height(8.dp))
        Text(name, style = SmallBold, color = tokens.inkSoft, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------------------------------
// Guided mode

/** The "display over other apps" switch is turned on, and the floating card drops in. */
@Composable
fun OverlayArt() = Art(4400) { c ->
    val t = tokens
    val on = c.ease(0.4f, 0.48f)
    Text("Display over other apps", style = SmallBold, color = t.ink)
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.raised).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChalaidenTile(26)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Chalaiden", style = Small, color = t.ink)
            Text(if (on > 0.5f) "Allowed" else "Not allowed", style = Tiny, color = t.inkSoft)
        }
        Toggle(on, Modifier.tap(c, 0.38f))
    }
    Spacer(Modifier.height(12.dp))
    val card = c.ease(0.56f, 0.66f)
    if (card > 0f) {
        GuideCardMock("Choose Payment", "Usually 4",
            Modifier.graphicsLayer { alpha = card; translationY = -(1f - card) * 24.dp.toPx() })
    } else {
        Bars(0.9f, 0.7f)
    }
}

/** bKash asks; the Chalaiden keyboard types the reply into its box by itself; Send; the card moves on. */
@Composable
fun AutoFillArt() = Art(5200) { c ->
    val t = tokens
    val next = c >= 0.72f
    GuideCardMock(
        if (next) "Type the amount" else "Type the merchant number",
        if (next) "250" else "01712 345 678",
        chip = "Typed for you",
    )
    Spacer(Modifier.height(30.dp))
    UssdDialog(c, sendAt = 0.6f) {
        Text(if (next) "Enter Amount:" else "Enter Merchant bKash Account No:", style = Tiny, color = t.ink)
        Spacer(Modifier.height(5.dp))
        // Typed a digit at a time, quickly, so it reads as the keyboard doing it rather than a paste.
        val typed = when {
            next -> if (c >= 0.86f) "250".take(((c - 0.86f) / 0.02f).toInt() + 1) else ""
            else -> "01302277488".take(((c - 0.2f) / 0.012f).toInt().coerceIn(0, 11))
        }
        Field(typed, focused = true)
    }
}

/** The PIN goes into bKash's box, dot by dot, typed by the user on the PIN pad; then Send. */
@Composable
fun PinArt() = Art(5200) { c ->
    val t = tokens
    val dots = (0..4).count { c >= 0.22f + it * 0.07f }
    GuideCardMock("Enter your PIN", "Typed by you, in bKash's box")
    Spacer(Modifier.height(14.dp))
    UssdDialog(c, sendAt = 0.64f) {
        Text("Enter PIN:", style = Tiny, color = t.ink)
        Spacer(Modifier.height(5.dp))
        Field("•".repeat(dots), focused = true)
    }
}

// ---------------------------------------------------------------------------------------------------
// Parts

/** A system prompt sliding up over a dimmed screen, Allow tapped, then a result pill. */
@Composable
private fun PromptArt(
    title: String,
    allow: String,
    deny: String,
    result: String,
    icon: @Composable () -> Unit,
) = Art(4800) { c ->
    val t = tokens
    val show = c.ease(0.08f, 0.2f)
    val gone = c.ease(0.6f, 0.68f)
    val v = show * (1f - gone)
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChalaidenTile(22)
                Spacer(Modifier.width(8.dp))
                Text("Chalaiden", style = SmallBold, color = t.ink)
            }
            Spacer(Modifier.height(10.dp))
            Bars(0.9f, 0.75f, 0.85f)
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (if (t.isDark) 0.6f else 0.32f) * v)))
        if (v > 0f) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth()
                    .graphicsLayer { alpha = v; translationY = (1f - show) * 30.dp.toPx() }
                    .clip(RoundedCornerShape(16.dp)).background(t.raised).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Spacer(Modifier.height(6.dp))
                Text(title, style = SmallBold, color = t.ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().tap(c, 0.48f).clip(RoundedCornerShape(50)).background(t.accent).padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(allow, style = Tiny.copy(fontWeight = FontWeight.SemiBold), color = t.onAccent) }
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(deny, style = Tiny, color = t.inkSoft)
                }
            }
        }
        if (c >= 0.7f) {
            val s = c.pop(0.7f, 0.8f)
            Row(
                Modifier.align(Alignment.Center).graphicsLayer { scaleX = s; scaleY = s }
                    .clip(RoundedCornerShape(50)).background(t.raised).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckDot()
                Spacer(Modifier.width(6.dp))
                Text(result, style = Small, color = t.ink)
            }
        }
    }
}

/** A phone USSD dialog: the content, then Cancel and Send (tapped at [sendAt]). */
@Composable
private fun UssdDialog(c: Float, modifier: Modifier = Modifier, sendAt: Float? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.raised).padding(10.dp)) {
        content()
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Cancel", style = Tiny, color = t.accent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            Text(
                "Send", style = Tiny.copy(fontWeight = FontWeight.SemiBold), color = t.accent,
                modifier = (if (sendAt != null) Modifier.tap(c, sendAt) else Modifier).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Chalaiden's floating guide card, small. */
@Composable
private fun GuideCardMock(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    chip: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = tokens
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.surface)
            .border(1.dp, t.line, RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, style = Tiny.copy(fontWeight = FontWeight.SemiBold), color = t.ink, modifier = Modifier.weight(1f), maxLines = 1)
            trailing?.invoke()
        }
        if (value != null) Text(value, style = SmallBold, color = t.ink)
        if (chip != null) {
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Check, null, tint = t.accent, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(3.dp))
                Text(chip, style = Tiny, color = t.inkSoft)
            }
        }
    }
}

/** A text box; [focused] gives it the accent outline and a caret. */
@Composable
private fun Field(text: String, focused: Boolean, modifier: Modifier = Modifier, hint: String? = null) {
    val t = tokens
    Row(
        modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (focused) t.accent else t.line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text.isEmpty() && hint != null && !focused) Text(hint, style = Small, color = t.inkSoft)
        Text(text, style = Small, color = t.ink)
        if (focused) Box(Modifier.padding(start = 1.dp).width(1.dp).height(12.dp).background(t.accent))
    }
}

@Composable
private fun MockRow(title: String, value: String) {
    val t = tokens
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(title, style = Small, color = t.ink)
        Text(value, style = Tiny, color = t.inkSoft)
    }
}

/** Grey lines standing in for text. */
@Composable
private fun Bars(vararg widths: Float) {
    val t = tokens
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        widths.forEach { Box(Modifier.fillMaxWidth(it).height(6.dp).clip(RoundedCornerShape(50)).background(t.raised)) }
    }
}

@Composable
private fun Pill(label: String, background: Color, ink: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, style = Tiny.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun CheckDot(modifier: Modifier = Modifier) {
    val t = tokens
    Box(modifier.size(15.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, null, tint = t.onAccent, modifier = Modifier.size(10.dp))
    }
}

/** A switch, [on] from 0 (off) to 1 (on). */
@Composable
private fun Toggle(on: Float, modifier: Modifier = Modifier) {
    val t = tokens
    Box(modifier.width(32.dp).height(19.dp).clip(RoundedCornerShape(50)).background(lerp(t.line, t.accent, on))) {
        Box(Modifier.offset(x = (3 + 13 * on).dp, y = 3.dp).size(13.dp).clip(CircleShape).background(Color.White))
    }
}

/** Shizuku's own app icon (from Shizuku, Apache 2.0), clipped like a launcher icon. */
@Composable
private fun ShizukuIcon(size: Int) {
    Image(
        painterResource(R.drawable.shizuku_icon), contentDescription = null,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)),
    )
}

/** Chalaiden's own mark on a tile. */
@Composable
private fun ChalaidenTile(size: Int) {
    val t = tokens
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(t.raised), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size((size * 0.6f).dp))
    }
}

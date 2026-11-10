package com.chalaiden.app.ui

import androidx.compose.runtime.produceState
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import com.chalaiden.app.ui.components.FixedTextScale
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import android.provider.Settings
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
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
    /** Which of the [HomeDesign]s is up top, and where a swipe to another one is kept. */
    design: Int = 0,
    onDesign: (Int) -> Unit = {},
) {
    val t = tokens
    val pager = rememberPagerState(initialPage = design.coerceIn(0, HomeDesign.entries.lastIndex)) { HomeDesign.entries.size }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onDesign) }
    // One stack, top down: the brand (and anything needing attention), then the actions straight under it.
    // Scrolls only when text is very large.
    Box(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column {
                Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp)) {
                    HomeMenu(onSetup, Modifier.align(Alignment.CenterEnd).offset(x = 12.dp))
                }
                // The brand comes in more than one design; a swipe left or right changes it. Edge to edge, so a
                // design slides off the screen rather than into the margin.
                // Only the page on screen is laid out, so the pager is as tall as that design, not the tallest one
                // (at a large text size the chat runs far longer than the big type). The height eases between them.
                HorizontalPager(pager, Modifier.fillMaxWidth().animateContentSize(tween(250)), verticalAlignment = Alignment.Top) { page ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        val active = pager.settledPage == page
                        when (HomeDesign.entries[page]) {
                            HomeDesign.Chat -> ChatDesign(active)
                            HomeDesign.BigType -> BigTypeDesign(active)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                PageDots(pager.currentPage, HomeDesign.entries.size, Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (keyboardOff) {
                        // A session was cut off before it could switch the keyboards back on, and no root or Shizuku
                        // is here to do it now; the user can, in Settings.
                        Callout(t.error, "Your keyboard is off", "Turn it back on in Settings, or start Shizuku and reopen Chalaiden", onFixKeyboard)
                    }
                    if (!setupDone) Callout(t.accent, "Finish setup", "So Chalaiden can answer bKash for you", onSetup)
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                // One SIM needs no choosing, so nothing is shown. With more, a pill shows which one bKash is
                // dialled on; a tap moves to the next.
                if (sims.size > 1) {
                    SimPill(sims, simId, onSelectSim)
                    Spacer(Modifier.height(24.dp))
                }
                // Four large blocks, two by two, right under the brand. Pay opens the scanner, which can switch to typing the number.
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

/** The designs Home's brand can wear. Kept by position in [com.chalaiden.app.ussd.Prefs.homeDesign]: add new ones at the end. */
enum class HomeDesign { Chat, BigType }

/** Which design is showing: a short pill for the current one, dots for the rest. */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 18.dp else 6.dp, tween(220), label = "dot")
            Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(if (i == current) t.ink else t.line))
        }
    }
}

private fun animationsOff(context: android.content.Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Each design's entrance plays once per launch, the first time it is on screen; not on every return to Home. */
private val played = mutableSetOf<HomeDesign>()

/** Waits until [active], then runs [entrance] once per launch for [design]; [skip] when it already ran or motion is off. */
@Composable
private fun Entrance(design: HomeDesign, active: Boolean, skip: suspend () -> Unit, entrance: suspend CoroutineScope.() -> Unit) {
    val context = LocalContext.current
    val isActive by rememberUpdatedState(active)
    LaunchedEffect(Unit) {
        if (design in played || animationsOff(context)) { played += design; skip(); return@LaunchedEffect }
        snapshotFlow { isActive }.first { it }
        played += design
        // Leaving the page mid-way doesn't stop it: the entrance finishes off screen.
        coroutineScope { entrance() }
    }
}

/** A message bubble coming in: up from below and out from its tail, with a small overshoot. */
private fun Modifier.arriving(progress: () -> Float, tail: TransformOrigin) = graphicsLayer {
    val p = progress()
    alpha = p.coerceIn(0f, 1f)
    scaleX = 0.7f + 0.3f * p
    scaleY = 0.7f + 0.3f * p
    translationY = (1f - p) * 14.dp.toPx()
    transformOrigin = tail
}

private val Bounce = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)

/**
 * The meme is an answer, so this design opens on the question it answers: under the logo, the
 * user's worry in grey on the left and Chalaiden's reply, "chalaiden" (চালাই দেন, just run it), in a blue bubble on
 * the right. The first time it shows in a launch, the two messages arrive, Chalaiden types, and the reply
 * pops in.
 */
@Composable
private fun ChatDesign(active: Boolean) {
    val t = tokens
    val first = remember { Animatable(0f) }
    val second = remember { Animatable(0f) }
    val reply = remember { Animatable(0f) }
    var typing by remember { mutableStateOf(false) }
    Entrance(
        HomeDesign.Chat, active,
        skip = { first.snapTo(1f); second.snapTo(1f); reply.snapTo(1f) },
    ) {
        // After Home's own fade-in, so the first message isn't missed.
        delay(450)
        launch { first.animateTo(1f, Bounce) }
        delay(500)
        launch { second.animateTo(1f, Bounce) }
        delay(450)
        typing = true
        delay(900)
        typing = false
        reply.animateTo(1f, Bounce)
    }
    Column(Modifier.fillMaxWidth()) {
        Image(
            painterResource(R.drawable.logo_mark),
            contentDescription = "Chalaiden",
            modifier = Modifier.height(40.dp).aspectRatio(42f / 36f).semantics { heading() },
        )
        Spacer(Modifier.height(20.dp))
        // Every bubble is laid out from the start, so nothing shifts as they arrive.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Asked("bKash e taka pathabo, data nai, app o nai…", RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)) { first.value }
            Asked("ki korbo?", RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)) { second.value }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.Bottom) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    // One line always: in a narrow window (a floating window, a small phone at a large
                    // display size) the text steps down to fit rather than being cut off mid-word.
                    BasicText(
                        "chalaiden.",
                        style = Type.display.copy(
                            fontWeight = FontWeight.Bold, fontSize = 32.sp.capScale(), lineHeight = 40.sp.capScale(),
                            letterSpacing = (-1.0).sp, color = t.onAccent,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 32.sp.capScale(), stepSize = 1.sp),
                        modifier = Modifier
                            .arriving({ reply.value }, TransformOrigin(1f, 1f))
                            .clip(RoundedCornerShape(26.dp, 26.dp, 6.dp, 26.dp))
                            .background(t.accent)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    if (typing) TypingDots()
                }
            }
            Text(
                clockNow(),
                style = Type.label.copy(fontSize = 12.sp),
                color = t.inkSoft,
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp).graphicsLayer { alpha = reply.value.coerceIn(0f, 1f) },
            )
        }
    }
}

/**
 * The wordmark at poster size, "chalai den", set tight, with the logo's blue dot as its full stop. The
 * first time it shows in a launch, the dot drops in and bounces.
 */
@Composable
private fun BigTypeDesign(active: Boolean) {
    val t = tokens
    val drop = remember { Animatable(0f) }
    Entrance(HomeDesign.BigType, active, skip = { drop.snapTo(1f) }) {
        delay(150)
        drop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 380f))
    }
    Column(Modifier.fillMaxWidth()) {
        Image(
            painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.height(40.dp).aspectRatio(42f / 36f),
        )
        Spacer(Modifier.height(16.dp))
        // Part of the picture, like the logo: it fills the width at any text size.
        FixedTextScale {
            BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Chalaiden"; heading() }) {
                val size = (maxWidth.value * 0.335f).sp
                val style = Type.wordmark.copy(
                    fontWeight = FontWeight.ExtraBold, fontSize = size, lineHeight = size * 0.86f, letterSpacing = (-0.075).em,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                )
                // One text, so the two lines share one tight leading; the dot rides on the baseline after "den".
                Text(
                    buildAnnotatedString {
                        append("chalai\nden")
                        appendInlineContent("dot")
                    },
                    style = style,
                    color = t.ink,
                    softWrap = false,
                    inlineContent = mapOf(
                        "dot" to InlineTextContent(Placeholder(0.27.em, 0.22.em, PlaceholderVerticalAlign.AboveBaseline)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                                Box(
                                    Modifier
                                        .graphicsLayer {
                                            val p = drop.value
                                            alpha = (p * 3f).coerceIn(0f, 1f)
                                            translationY = (1f - p) * -size.toPx() * 1.2f
                                        }
                                        .fillMaxHeight()
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(t.accent),
                                )
                            }
                        },
                    ),
                    modifier = Modifier.offset(x = (-4).dp),
                )
            }
        }
    }
}

/**
 * The time now, as a chat app stamps a message: "10:34 PM", or "22:34" when the phone uses a 24-hour
 * clock. Moves on with the minute while Home stays open.
 */
@Composable
private fun clockNow(): String {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now))
}

/** One of the user's grey bubbles. */
@Composable
private fun ColumnScope.Asked(text: String, shape: RoundedCornerShape, progress: () -> Float) {
    val t = tokens
    Text(
        text,
        style = Type.body,
        color = t.ink,
        modifier = Modifier.fillMaxWidth(0.84f).wrapContentWidth(Alignment.Start)
            .arriving(progress, TransformOrigin(0f, 1f)).clip(shape).background(t.surface)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Chalaiden typing: three dots in a small bubble, rising in turn. */
@Composable
private fun TypingDots() {
    val t = tokens
    val wave = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(t.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val a by wave.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(keyframes { durationMillis = 900; 0.3f at 0; 1f at 200 + i * 150; 0.3f at 600 + i * 150 }),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(t.inkSoft))
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
        // Top, with the dot and chevron centred on the title's first line: at a large text size the
        // detail wraps to several lines, and centred marks would float beside the middle of it.
        verticalAlignment = Alignment.Top,
    ) {
        val line = with(LocalDensity.current) { Type.title.lineHeight.toDp() }
        Box(Modifier.padding(top = (line - 8.dp) / 2).size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title, color = t.ink)
            Text(detail, style = Type.label, color = t.inkSoft)
        }
        Icon(
            AppIcons.ChevronRight, contentDescription = null, tint = t.inkSoft,
            modifier = Modifier.padding(top = ((line - 20.dp) / 2).coerceAtLeast(0.dp)).size(20.dp),
        )
    }
}

@Composable
private fun SimPill(sims: List<Sim>, simId: Int?, onSelect: (Int) -> Unit) {
    val t = tokens
    val current = sims.firstOrNull { it.subscriptionId == simId }
    val next = sims[(sims.indexOf(current) + 1).mod(sims.size)]
    Row(
        // At least 56dp, and taller when the text is: a fixed height would cut large text off.
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(50)).background(t.surface)
            .clickable(role = Role.Button, onClickLabel = "Use ${simName(next)}") { onSelect(next.subscriptionId) }
            .padding(start = 18.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
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
                Text(sim?.let { "SIM ${it.slot + 1}" } ?: "Choose a SIM", style = Type.button, color = t.ink, maxLines = 1, softWrap = false)
                sim?.let(::carrierOf)?.let {
                    Text(" · $it", style = Type.body, color = t.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("Change", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.ink)
    }
}

/**
 * The carrier's name, or null when there is none to show: with no name from the network, [com.chalaiden.app.ussd.Dialer]
 * names the SIM by its slot ("SIM 2"), which the pill already says.
 */
private fun carrierOf(sim: Sim): String? = sim.carrier.takeIf { it.isNotBlank() && it != "SIM ${sim.slot + 1}" }

private fun simName(sim: Sim) = listOfNotNull("SIM ${sim.slot + 1}", carrierOf(sim)).joinToString(", ")

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

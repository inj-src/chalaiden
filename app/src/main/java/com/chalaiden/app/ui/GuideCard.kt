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
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.chalaiden.app.R
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
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.chalaiden.app.ussd.GuideStep
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.GuideSession

/**
 * The step in front of the user, kept to one line above bKash's dialog, with the mark that says whose card
 * this is. With the Chalaiden keyboard it follows bKash by itself: each new box moves it on. Typing with
 * another keyboard, Chalaiden can't see bKash, so the card moves with a swipe instead (left for the next
 * step, right to go back), and the step's value is copied for a long-press paste. [floating]: in [GuideCover].
 */
@Composable
fun GuideCard(guide: GuideSession.State, floating: Boolean) {
    val t = tokens
    val context = LocalContext.current
    val step = guide.step
    val typing = remember(guide.index, guide.filled) { ChalaidenKeyboard.isCurrent(context) }
    // Android shows its "Copied" preview on every copy and offers apps no way to hide it, so copy only
    // when someone has to paste: never while the Chalaiden keyboard is typing the replies.
    LaunchedEffect(guide.index, guide.flow) {
        if (!ChalaidenKeyboard.isCurrent(context)) step.value?.let { copy(context, it) }
    }
    // The pill stays put; only the words in it follow a swipe (see [swipeSteps]).
    val drag = remember { Animatable(0f) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (floating) 8.dp else 0.dp)) {
        Row(
            Modifier.fillMaxWidth().swipeSteps(drag).clip(RoundedCornerShape(50)).background(t.surface)
                .border(1.dp, t.line, RoundedCornerShape(50)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Next step") { GuideSession.next(); true },
                        CustomAccessibilityAction("Previous step") { GuideSession.back(); true },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chalaiden's mark first: this card floats over bKash's dialogs and must say whose it is.
            // Sized to the pill's rounded end, so the mark sits in it like a coin in a slot.
            Image(painterResource(R.drawable.logo_mark), contentDescription = "Chalaiden", modifier = Modifier.height(28.dp).aspectRatio(42f / 36f))
            // Whenever the step changes (a swipe, or the keyboard following bKash), the old words slide away
            // the way the step moved and the new ones come in behind them. Clipped to the pill's middle, so
            // they pass under its edge, not over the mark or the ✕.
            AnimatedContent(
                targetState = guide.index,
                transitionSpec = {
                    val way = if (targetState > initialState) 1 else -1
                    // The two cross over: the new words are already arriving as the old ones leave.
                    (slideInHorizontally(tween(300, easing = EaseOut)) { w -> way * w / 2 } + fadeIn(tween(240)))
                        .togetherWith(slideOutHorizontally(tween(240, easing = EaseIn)) { w -> -way * w / 2 } + fadeOut(tween(200)))
                        .using(SizeTransform(clip = false))
                },
                label = "guide-step",
                // The fade sits in the padding, so words at rest are never touched by it.
                modifier = Modifier.weight(1f).clipToBounds().fadeEdges().padding(horizontal = FadeEdge, vertical = 4.dp).graphicsLayer {
                    translationX = drag.value
                    alpha = 1f - (abs(drag.value) / size.width).coerceIn(0f, 0.4f)
                },
            ) { index ->
                val s = guide.steps[index]
                Column {
                    Text(s.title, style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = t.ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    // What to do now: check and send what the keyboard typed, or paste the value and swipe on.
                    val how = when {
                        s.kind == GuideStep.Kind.Pin -> if (typing) "Type it on the PIN pad, then Send" else null
                        typing -> "Check bKash's box, then tap Send"
                        // A menu pick has no value to paste, but bKash's usual number for it helps.
                        else -> listOfNotNull(
                            s.value ?: s.fill?.takeIf { s.kind == GuideStep.Kind.Pick }?.let { "Usually $it" },
                            "swipe for next".takeIf { index < guide.steps.lastIndex },
                        ).joinToString(" · ")
                    }
                    how?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = Type.label, color = t.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            CardIcon(AppIcons.Cross, "Close the guide") { GuideSession.end(GuideSession.End.Closed) }
        }
    }
}

private val FadeEdge = 12.dp

/** Words leaving or entering the pill's middle thin out over its last [FadeEdge] instead of being cut off. */
private fun Modifier.fadeEdges(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = FadeEdge.toPx() / size.width
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** How far a swipe must travel, or how fast, to change the step. */
private val SwipeDistance = 64.dp
private const val SWIPE_VELOCITY = 900f // px/s

/**
 * A sideways swipe anywhere on the pill moves its words with the finger, 1:1 ([drag] is their offset). Let
 * go past [SwipeDistance] (or flicked) and the step changes: the card's own transition slides the old words
 * on out while [drag] eases home. Short of that they spring back. Past the first or last step they only
 * give a little, so there is nowhere to go.
 */
private fun Modifier.swipeSteps(drag: Animatable<Float, *>): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val distance = with(LocalDensity.current) { SwipeDistance.toPx() }
    pointerInput(Unit) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking(); scope.launch { drag.stop() } },
            onHorizontalDrag = { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                val g = GuideSession.state.value ?: return@detectHorizontalDragGestures
                val toward = drag.value + dx
                val walled = (toward < 0 && g.isLast) || (toward > 0 && g.index == 0)
                scope.launch { drag.snapTo(if (walled) drag.value + dx * 0.2f else toward) }
            },
            onDragEnd = {
                val v = tracker.calculateVelocity().x
                val g = GuideSession.state.value
                val forward = g != null && !g.isLast && (drag.value < -distance || (v < -SWIPE_VELOCITY && drag.value < 0))
                val backward = g != null && g.index > 0 && (drag.value > distance || (v > SWIPE_VELOCITY && drag.value > 0))
                if (forward || backward) {
                    if (forward) GuideSession.next() else GuideSession.back()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), v) }
            },
            onDragCancel = { scope.launch { drag.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) } },
        )
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

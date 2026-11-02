package com.chalaiden.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * An arc that spins while its length breathes in and out, so waiting never looks frozen.
 * [track] draws a faint full ring behind it.
 */
@Composable
fun Spinner(color: Color, size: Dp, modifier: Modifier = Modifier, stroke: Dp = 3.dp, track: Color? = null) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rotation")
    val sweep by transition.animateFloat(
        28f, 250f,
        infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(this.size.width - w, this.size.height - w)
        if (track != null) drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(color, rotation - 90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A tick that draws itself in, stroke by stroke, the moment it appears. */
@Composable
fun AnimatedCheck(color: Color, size: Dp, modifier: Modifier = Modifier, stroke: Dp = 2.4.dp, delayMillis: Int = 0, durationMillis: Int = 380) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMillis, delayMillis, FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val path = Path().apply {
            moveTo(5f * s, 12.5f * s)
            lineTo(10f * s, 17.5f * s)
            lineTo(19f * s, 7f * s)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * progress.value, partial, true)
        drawPath(partial, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Success badge: the disc springs in with a little overshoot, then the tick draws itself.
 * [onShown] fires once the tick is complete (a good moment for a haptic).
 */
@Composable
fun SuccessBadge(disc: Color, tick: Color, size: Dp, modifier: Modifier = Modifier, onShown: () -> Unit = {}) {
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow)) }
        kotlinx.coroutines.delay(420)
        onShown()
    }
    Box(modifier.size(size).scale(scale.value), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawCircle(disc) }
        AnimatedCheck(tick, size * 0.56f, stroke = 3.dp, delayMillis = 140, durationMillis = 420)
    }
}

/** For the other outcomes: the badge springs in without the drawing. */
@Composable
fun PopIn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
    Box(modifier.scale(scale.value), contentAlignment = Alignment.Center) { content() }
}

private operator fun Dp.times(f: Float): Dp = (value * f).dp

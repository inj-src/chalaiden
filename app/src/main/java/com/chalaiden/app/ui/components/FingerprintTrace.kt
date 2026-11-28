package com.chalaiden.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext

/*
 * The fingerprint trace from Android's own fingerprint setup: its ridges draw on one after another,
 * then wipe away. Paths and timings are from AOSP Settings,
 * res/drawable/enrollment_fingerprint_isolated{,_animation}.xml and res/anim/enrollment_fingerprint_
 * isolated_ridge_*_path_animation.xml. Copyright (C) 2015 The Android Open Source Project, licensed
 * under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0). Redrawn here in
 * Compose so it can loop and take the app's colours.
 */

/** One ridge: the head draws it on, the tail wipes it off (ms from the start of a pass). Reversed ridges run end to start. */
private class Ridge(
    val path: String,
    val headDelay: Int,
    val headMs: Int,
    val tailDelay: Int,
    val tailMs: Int,
    val reversed: Boolean = false,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

private val RIDGES = listOf(
    Ridge("M 24.9488677979,32.3508300781 c -1.81059265137,0.338500976562 -3.58520507812,0.447387695312 -4.62879943848,0.447387695312 c -4.12730407715,0.0 -8.05894470215,-0.96842956543 -11.5207061768,-3.45275878906 c -5.33699035645,-3.830078125 -8.56369018555,-10.0885009766 -8.56369018555,-17.1589355469", 166, 900, 600, 833),
    Ridge("M -9.23379516602,40.8356933594 c -3.24549865723,-3.46032714844 -5.1540222168,-5.77195739746 -7.87710571289,-10.9068603516 c -2.76379394531,-5.21166992188 -4.04838562012,-11.3482666016 -4.04838562012,-17.6915283203 c 0.0,-11.6563720703 9.44940185547,-21.1059570312 21.1058959961,-21.1059570312 c 11.6564941406,0.0 21.1058959961,9.44958496094 21.1058959961,21.1059570312", 100, 966, 533, 900),
    Ridge("M -28.8249053955,28.5169677734 c -2.41259765625,-6.82202148438 -2.85319519043,-12.3121337891 -2.85319519043,-16.3226318359 c 0.0,-4.64868164062 0.792999267578,-9.06323242188 2.59269714355,-13.0396728516 c 4.96929931641,-10.9801025391 16.0211029053,-18.619140625 28.857208252,-18.619140625 c 17.4846954346,0.0 31.6587982178,14.1740722656 31.6587982178,31.6588134766 c 0.0,5.82824707031 -4.72470092773,10.5529785156 -10.5529022217,10.5529785156 c -5.82820129395,0.0 -10.5529937744,-4.72473144531 -10.5529937744,-10.5529785156 c 0.0,-5.82824707031 -4.72470092773,-10.5529785156 -10.5529022217,-10.5529785156 c -5.82820129395,0.0 -10.5529022217,4.72473144531 -10.5529022217,10.5529785156 c 0.0,8.17932128906 3.10879516602,15.5925292969 8.25030517578,21.0004882812 c 3.88919067383,4.09069824219 7.77758789062,6.64123535156 14.2838897705,8.52136230469", 0, 866, 400, 900, reversed = true),
    Ridge("M -34.4861907959,-11.6943359375 c 3.78790283203,-5.64636230469 8.36389160156,-9.94665527344 14.3594970703,-13.2164306641 c 5.99560546875,-3.26977539062 12.8716125488,-5.1279296875 20.1817016602,-5.1279296875 c 7.27980041504,0.0 14.129196167,1.84289550781 20.1071014404,5.08740234375 c 5.97790527344,3.24450683594 10.7957000732,7.759765625 14.5897064209,13.3666992188", 366, 633, 733, 533, reversed = true),
    Ridge("M 121.472564697,107.859741211 c -7.39790344238,-4.03979492188 -15.2462921143,-6.34167480469 -24.3116912842,-6.34167480469 c -9.06539916992,0.0 -16.2951049805,2.40405273438 -23.12550354,6.34167480469", 233, 566, 700, 433, dx = -97.5f, dy = -142.5f),
)

private const val VIEW_W = 75f
private const val VIEW_H = 88f
private const val ORIGIN_X = 37.5835f
private const val ORIGIN_Y = 43.66685f

/** One pass is ~1.4s; the rest of the cycle is a rest with only the faint print showing. */
private const val CYCLE_MS = 2400

/** AOSP's stroke, in viewport units (the canvas is scaled to the viewport). */
private val LINE = Stroke(width = 5f, cap = StrokeCap.Round)

/** AOSP's wipe-off curves are path interpolators; a gentle ease-in-out is close. */
private val Wipe: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * A fingerprint that traces itself in [color] over a faint copy of itself, looping. Still, and fully
 * drawn, when the phone has animations turned off. Size it by width or height; it keeps 75:88.
 */
@Composable
fun FingerprintTrace(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val paths = remember { RIDGES.map { PathParser().parsePathString(it.path).toPath() } }
    val measures = remember { paths.map { PathMeasure().apply { setPath(it, false) } } }
    val time = if (still) null else rememberInfiniteTransition(label = "fingerprint").animateFloat(
        initialValue = 0f,
        targetValue = CYCLE_MS.toFloat(),
        animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "fingerprint time",
    )
    val segment = remember { Path() }

    Canvas(modifier.aspectRatio(VIEW_W / VIEW_H)) {
        val k = size.width / VIEW_W
        scale(k, k, pivot = Offset.Zero) {
            translate(ORIGIN_X, ORIGIN_Y) {
                val t = time?.value
                RIDGES.forEachIndexed { i, ridge ->
                    translate(ridge.dx, ridge.dy) {
                        drawPath(paths[i], color.copy(alpha = if (t == null) 1f else 0.22f), style = LINE)
                        if (t == null) return@translate
                        val head = progress(t, ridge.headDelay, ridge.headMs, FastOutSlowInEasing)
                        val tail = progress(t, ridge.tailDelay, ridge.tailMs, Wipe)
                        // Nothing yet, or nearly gone: a sliver would draw as a lone round cap, a dot.
                        if (head - tail < 0.02f) return@translate
                        val m = measures[i]
                        val len = m.length
                        val (from, to) = if (ridge.reversed) (1f - head) to (1f - tail) else tail to head
                        segment.reset()
                        m.getSegment(from * len, to * len, segment, true)
                        drawPath(segment, color, style = LINE)
                    }
                }
            }
        }
    }
}

/** 0 before [delay], 1 after [delay] + [duration], eased in between. */
private fun progress(t: Float, delay: Int, duration: Int, easing: Easing): Float =
    easing.transform(((t - delay) / duration).coerceIn(0f, 1f))

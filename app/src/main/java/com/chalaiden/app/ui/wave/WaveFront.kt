/*
 * Ported from DistortionWavesKit (https://github.com/Jakubantalik/Libraries.dev, packages/distortion-waves),
 * Copyright (c) 2026 Jakub Antalik, MIT License. The full licence ships in assets/licenses/distortion-waves.txt.
 * Only the vertical wave is ported: up the screen from a button at the bottom, without leaning to a touch.
 */
package com.chalaiden.app.ui.wave

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the wave looks and moves. All lengths are dp, all times seconds. The defaults are the kit's tuned
 * vertical wave in its colourful palette.
 */
data class WaveStyle(
    // Timing
    val anticipation: Float = 0.5f,
    val travel: Float = 2.28f,
    val linger: Float = -0.35f,
    val rimFade: Float = 3.03f,
    val rimEasing: Easing = Easing(0.42f, 0f, 0.58f, 1f),
    val easing: Easing = Easing(0.22f, 1f, 0.36f, 1f),
    // Gravity
    val pull: Float = 24f,
    val inhale: Float = 56f,
    val gatherGlow: Float = 0.33f,
    // Swell
    val amplitude: Float = 34f,
    val lensWidth: Float = 150f,
    val aberration: Float = 0.78f,
    val swell: Float = 22f,
    // Progressive blur
    val blur: Float = 18f,
    val blurAhead: Float = 90f,
    val blurBehind: Float = 240f,
    // Colour on the front
    val lead: Float = 130f,
    val tail: Float = 300f,
    val strength: Float = 0.92f,
    // Colour on the edges
    val rimWidth: Float = 14f,
    val rimUnevenness: Float = 1f,
    val rimStrength: Float = 0.82f,
    val rimSoftness: Float = 343f,
    val rimPulse: Float = 0.6f,
    val hairlineWidth: Float = 1.5f,
    val hairlineOpacity: Float = 0.9f,
    val hairlineWhite: Float = 0.95f,
    val hairlineLinger: Float = 0.47f,
    val hairlineFade: Float = 3f,
    // Dots
    val particleCount: Int = 64,
    val particleSize: Float = 1.8f,
    val particleReach: Float = 150f,
    val particleFlight: Float = 30f,
    val particleGap: Float = 24f,
    val particleLife: Float = 2.19f,
    val palette: WavePalette = WavePalette.Colorful,
) {
    /** Seconds for the whole of it: the swell settled, the edges cleared, the last dot faded. */
    val duration: Float
        get() = anticipation + maxOf(travel * 0.6f, linger + rimFade, linger + hairlineLinger + hairlineFade, particleLife)
}

/** Colours flowing along the front and round the edges, then a fringe ahead of the front and one behind. */
class WavePalette(val colors: List<Int>, val ahead: Int, val behind: Int) {
    /** OKLab triplets, the colours then the two fringes, as the band shader reads them. */
    val oklab: FloatArray by lazy { (colors + ahead + behind).flatMap { oklab(it).asList() }.toFloatArray() }

    companion object {
        /** The kit's colourful palette: a pink fringe ahead, a blue one behind. */
        val Colorful = WavePalette(
            listOf(0xFFC915, 0x7EC4FF, 0xB428E6, 0xEB64A0, 0xFFB07A, 0x9AA0FF, 0x7FD9EE),
            ahead = 0xFF7AB6,
            behind = 0x5ABEFF,
        )

        private fun oklab(rgb: Int): FloatArray {
            fun linear(v: Int): Double {
                val c = v / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = linear(rgb shr 16 and 0xFF)
            val g = linear(rgb shr 8 and 0xFF)
            val b = linear(rgb and 0xFF)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return floatArrayOf(
                (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s).toFloat(),
                (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s).toFloat(),
                (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s).toFloat(),
            )
        }
    }
}

/** A timing curve with CSS `cubic-bezier()` control points. */
data class Easing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    operator fun invoke(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // x grows with the curve's parameter, so halving always finds it.
        var low = 0f
        var high = 1f
        repeat(24) {
            val s = (low + high) / 2
            if (bezier(s, x1, x2) < x) low = s else high = s
        }
        return bezier((low + high) / 2, y1, y2)
    }

    private fun bezier(s: Float, a: Float, b: Float): Float {
        val r = 1 - s
        return 3 * r * r * s * a + 3 * r * s * s * b + s * s * s
    }
}

/**
 * Where the wave is at one moment of its life: a pure function of [progress], so it can be scrubbed and
 * tested. Gravity pulls everything toward the source; the front leaves the source's outline (a pill),
 * opens into an arc and crosses the screen; the colour it leaves on the edges is wiped by a later front.
 *
 * [source] and [size] are in dp, in the wave's own box.
 */
class WaveFront(progress: Float, val source: Rect, val size: Size, style: WaveStyle) {
    val originX = source.center.x
    val originY = source.center.y
    /** Half-length of the straight part of the pill the front is an offset of. */
    val spine: Float
    /** Distance of the front from that spine. */
    val radius: Float
    /** How far the front is along its way, 0–1. */
    val moved: Float
    /** Scale of the x axis in the field: above 1 the front bows. */
    val squash: Float
    val time: Float
    /** How strongly the swell and the blur act, 0–1: 0 at both ends, so nothing pops. */
    val energy: Float
    val swell: Float
    /** How far content is pulled toward the source, dp. */
    val gravity: Float
    /** How strong the colour on the front is, 0–1. */
    val glow: Float
    /** Distance of the later front that wipes the colour off the edges. */
    val clearRadius: Float
    /** Distance of the front that wipes the hairline off them, later still. */
    val hairClearRadius: Float
    /** Opacity of the colour on the edges, 0–1. */
    val rim: Float
    /** How far the dots have faded in before the launch, 0–1. */
    val sparkle: Float
    /** Seconds since the front left the source, 0 before that. */
    val flying: Float
    /** True once the front has left the source. */
    val launched: Boolean
    /** True once the front has crossed the whole screen: nothing of the outgoing side can show any more. */
    val hasCleared: Boolean

    init {
        val t = progress.coerceIn(0f, 1f) * style.duration
        val sinceLaunch = t - style.anticipation
        val gathered = smooth(0f, max(style.anticipation, 0.001f), t)
        val x = (sinceLaunch / style.travel).coerceIn(0f, 1f)
        moved = style.easing(x)

        val startRadius = min(source.width, source.height) / 2
        val startSpine = max(0f, source.width / 2 - startRadius)
        val opening = smooth(0f, 0.45f, moved)

        // The radius at which the front has left the screen's farthest corner…
        var reach = 0f
        for ((cx, cy) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
            reach = max(reach, hypot((cx - originX) * ARC_SQUASH, cy - originY))
        }
        // …and has taken the outgoing side with it.
        val crossed = reach + (style.swell + COVER_ZONE) * ARC_SQUASH
        val settling = 1 - smooth(SETTLE_START, SETTLE_END, x)

        spine = startSpine * (1 - opening)
        radius = if (sinceLaunch < 0) startRadius + style.inhale * (1 - gathered)
        else startRadius + (crossed + OVERSHOOT - startRadius) * moved
        squash = 1 + (ARC_SQUASH - 1) * smooth(0f, 0.5f, moved)
        time = t
        energy = smooth(0f, 0.05f, x) * settling
        swell = style.swell * smooth(0f, 0.3f, moved)
        gravity = style.pull * gathered * (1 - smooth(0f, RELEASE, sinceLaunch))
        val gatheredGlow = min(GATHERED_GLOW * max(style.gatherGlow, 0f), 1f)
        glow = if (sinceLaunch < 0) gatheredGlow * gathered
        else (gatheredGlow + (1 - gatheredGlow) * smooth(0f, 0.07f, x)) * settling
        val clearStart = startRadius - style.rimSoftness
        val clearEnd = reach + (style.rimSoftness + style.swell) * ARC_SQUASH
        clearRadius = clearStart + (clearEnd - clearStart) * style.rimEasing((sinceLaunch - style.linger) / max(style.rimFade, 0.01f))
        hairClearRadius = clearStart + (clearEnd - clearStart) *
            style.rimEasing((sinceLaunch - style.linger - style.hairlineLinger) / max(style.hairlineFade, 0.01f))
        rim = style.rimStrength * smooth(0f, 0.1f, x) * (1 - smooth(0.9f, 1f, t / style.duration))
        sparkle = smooth(0f, max(style.anticipation, 0.001f) * 0.35f, t)
        flying = max(sinceLaunch, 0f)
        launched = sinceLaunch > 0
        hasCleared = radius > crossed
    }

    /** True while there is anything to draw: the swell, the colour, the edges or the pull. */
    val live: Boolean get() = energy > 0 || glow > 0 || rim > 0 || gravity > 0

    companion object {
        /** Half the zone over which the two screens crossfade, dp (`coverZone` in the shaders). */
        const val COVER_ZONE = 60f
        /** How much an arc's x axis is squashed, so it crosses a tall screen as a curve. */
        private const val ARC_SQUASH = 1.4f
        /** Seconds gravity takes to let go after the launch. */
        private const val RELEASE = 0.22f
        private const val GATHERED_GLOW = 0.5f
        /** How far past having crossed the screen the front settles, dp. */
        private const val OVERSHOOT = 100f
        /** The part of the travel over which the swell, the blur and the colour die away. */
        private const val SETTLE_START = 0.2f
        private const val SETTLE_END = 0.55f

        fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}

/**
 * The lumps that make the colour on the edges uneven: seven round the perimeter, each with its own length
 * and depth, drifting and breathing. (centre along the perimeter, length, depth) per lump, flattened.
 */
internal fun rimLumps(time: Float, size: Size, cornerRadius: Float, pulse: Float): FloatArray {
    val ex = max(size.width / 2 - cornerRadius, 0f)
    val ey = max(size.height / 2 - cornerRadius, 0f)
    val perimeter = 4 * (ex + ey + 0.5f * Math.PI.toFloat() * cornerRadius)
    val n = LUMPS.toFloat()
    val out = FloatArray(LUMPS * 3)
    for (i in 0 until LUMPS) {
        val fi = i.toFloat()
        val a = hash(fi * 12.9898f + 1.3f)
        val b = hash(fi * 78.233f + 4.1f)
        val c = hash(fi * 39.425f + 2.7f)
        val breath = (0.25f + 0.3f * pulse) * sin(time * (1.1f + 1.2f * a) * (1 + pulse) + fi * 2.4f)
        out[i * 3] = perimeter * (fi + 0.35f * a) / n + time * (-22 + 44 * b) * (1 + 2 * pulse)
        out[i * 3 + 1] = perimeter / n * (0.2f + 0.25f * c)
        out[i * 3 + 2] = (0.2f + 2.6f * b) * (1 + breath)
    }
    return out
}

internal const val LUMPS = 7

private fun hash(v: Float): Float {
    val x = sin(v.toDouble()) * 43758.5453
    return (x - floor(x)).toFloat()
}

/**
 * One tiny white dot. Before the launch it hops toward the source from somewhere above it and fades
 * well short of it; at the launch it is thrown out with the front, the quickest riding it, the slowest
 * trailing, swaying and twinkling until it fades.
 */
internal class WaveDot(index: Int, private val style: WaveStyle) {
    private val i = (index + 1).toFloat()
    private fun scatter(step: Float) = (i * step) % 1f
    /** Where across the source it leaves from, -1…1, short of the ends. */
    private val column = (scatter(0.7548776662f) * 2 - 1) * 0.92f
    private val pace = 0.35f + 0.72f * sqrt(scatter(0.8191725134f))
    private val drift = 15 + 50 * scatter(0.6710436067f)
    private val sway = 3 + 11 * scatter(0.4142135624f)
    private val swayRate = 2.5f + 3.5f * scatter(0.7320508076f)
    private val twinkleRate = 8 + 12 * scatter(0.2360679775f)
    private val phase = scatter(0.6180339887f) * 2 * Math.PI.toFloat()
    private val life = style.particleLife * (0.4f + 0.6f * scatter(0.6457513111f))
    val size = style.particleSize * (0.55f + 0.45f * scatter(0.5497004779f).let { it * it })
    private val brightness = 0.3f + 0.7f * scatter(0.6055512755f)
    private val hop = 0.16f + 0.14f * scatter(0.1231056256f)
    private val hopOffset = scatter(0.4721359550f)

    fun opacity(front: WaveFront): Float {
        val twinkle = 0.5f + 0.5f * sin(front.time * twinkleRate + phase)
        val glint = brightness * (0.7f + 0.3f * twinkle)
        if (!front.launched) {
            val along = hopAt(front.time).second
            val hopping = WaveFront.smooth(0f, 0.25f, along) * (1 - WaveFront.smooth(0.55f, 1f, along))
            val ending = 1 - WaveFront.smooth(style.anticipation * 0.75f, style.anticipation, front.time)
            return front.sparkle * hopping * ending * glint
        }
        return (1 - WaveFront.smooth(life * 0.55f, life, front.flying)) * glint
    }

    /** Centre, dp, in the wave's box. */
    fun point(front: WaveFront): Pair<Float, Float> = if (front.launched) outflow(front) else inflow(front)

    private fun hopAt(time: Float): Pair<Float, Float> {
        val hops = time / hop + hopOffset
        val count = floor(hops)
        return count to hops - count
    }

    private fun inflow(front: WaveFront): Pair<Float, Float> {
        val src = front.source
        val (count, along) = hopAt(front.time)
        val half = src.width / 2
        val face = src.height / 2
        val x = (pick(count, 1f) * 2 - 1) * (half + 24)
        val nearest = style.particleGap + style.particleFlight
        val away = nearest + max(style.particleReach - nearest, 0f) * pick(count, 2f)
        val startX = src.center.x + x
        val startY = src.center.y - (face + away)
        val corner = min(src.width, src.height) / 2
        val targetX = src.center.x + x.coerceIn(-half + corner, half - corner)
        val targetY = src.center.y - face
        val dx = targetX - startX
        val dy = targetY - startY
        val length = max(hypot(dx, dy), 1f)
        val travelled = style.particleFlight * along * along
        return (startX + dx / length * travelled) to (startY + dy / length * travelled)
    }

    private fun outflow(front: WaveFront): Pair<Float, Float> {
        val src = front.source
        val start = min(src.width, src.height) / 2
        val widening = 1 + FAN * front.moved * pace
        val swaying = sway * sin(swayRate * front.flying + phase) * min(front.flying / 0.25f, 1f)
        val x = column * src.width / 2 * widening + swaying
        val height = (start + (front.radius - start) * pace) * (1 - BOW * column * column) + drift * front.flying
        return (front.originX + x) to (front.originY - height)
    }

    private fun pick(n: Float, salt: Float) = hash(i * 12.9898f + n * 78.233f + salt * 37.719f)

    private companion object {
        /** How much wider than the source the quickest end up: the front is an arc, opening. */
        const val FAN = 0.22f
        /** How much lower they fly at the source's ends than in its middle, as the front does. */
        const val BOW = 0.3f
    }
}


/*
 * Ported from DistortionWavesKit (https://github.com/Jakubantalik/Libraries.dev, packages/distortion-waves),
 * Copyright (c) 2026 Jakub Antalik, MIT License. The full licence ships in assets/licenses/distortion-waves.txt.
 */
package com.chalaiden.app.ui.wave

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.RoundedCorner
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the wave opens from: the bounds of a view, in root coordinates. Set it with [waveSource]. */
@Stable
class WaveSource {
    internal var bounds by mutableStateOf<Rect?>(null)
}

/** Marks this view as where the wave opens from. */
fun Modifier.waveSource(source: WaveSource): Modifier = onGloballyPositioned { source.bounds = it.boundsInRoot() }

/**
 * A colourful distortion wave that carries [from] (a picture of the screen that was up) into [content].
 *
 * Everything falls toward the [source] for a moment, tiny sparks with it. Then the front leaves the source
 * and travels up the screen as one soft swell, carrying the sparks and the colour, with a blur and a
 * chromatic split peaking on it. The colour stays on the screen's edges after the middle has cleared.
 *
 * Plays once, as it first appears. [onReveal] fires as the front leaves the source and starts to uncover
 * [content]; [onFinish] once the edges have cleared. The system's animation scale applies, so with
 * animations off it ends at once. Before Android 13 (no runtime shaders) [content] shows as it is.
 */
@Composable
fun DistortionWave(
    from: ImageBitmap?,
    source: WaveSource,
    modifier: Modifier = Modifier,
    style: WaveStyle = remember { WaveStyle() },
    onReveal: () -> Unit = {},
    onFinish: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val reveal by rememberUpdatedState(onReveal)
    val finish by rememberUpdatedState(onFinish)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LaunchedEffect(Unit) { reveal(); finish() }
        return Box(modifier) { content() }
    }

    val progress = remember { Animatable(0f) }
    val done by remember { derivedStateOf { progress.value >= 1f } }
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { progress.value * style.duration >= style.anticipation + REVEAL_AFTER }.first { it }
            reveal()
        }
        progress.animateTo(1f, tween((style.duration * 1000).roundToInt(), easing = LinearEasing))
        finish()
    }

    val density = LocalDensity.current.density
    val view = LocalView.current
    val painter = remember(style, density) {
        // The colour on the edges follows the screen's own corners, where the phone reports them.
        val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        WavePainter(style, density, corner / density)
    }
    var box by remember { mutableStateOf(Rect.Zero) }

    // Recomputed per frame inside the draw and layer blocks only, so the wave never recomposes.
    fun front(): WaveFront? {
        if (box.isEmpty) return null
        val size = Size(box.width / density, box.height / density)
        val src = source.bounds?.translate(-box.topLeft)?.let {
            Rect(it.left / density, it.top / density, it.right / density, it.bottom / density)
        } ?: Rect(24f, size.height - 128f, size.width - 24f, size.height - 72f) // where the main button sits
        return WaveFront(progress.value, src, size, style)
    }

    Box(modifier.onGloballyPositioned { box = it.boundsInRoot() }) {
        // What the wave reveals: hidden until the front leaves the source, then shown behind it.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val f = front()
                if (done || f == null) {
                    alpha = if (f == null && !done) 0f else 1f
                    renderEffect = null
                    return@graphicsLayer
                }
                val acting = progress.value > 0f && f.energy > 0f
                alpha = if (f.launched) 1f else 0f
                renderEffect = painter.side(f, side = -1f, lens = acting, blurred = acting)?.asComposeRenderEffect()
            },
        ) { content() }

        if (!done) {
            // What it leaves: the screen that was up, falling toward the source, then swept away.
            if (from != null) {
                Spacer(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val f = front() ?: return@graphicsLayer
                            val live = progress.value > 0f
                            val acting = live && f.energy > 0f
                            alpha = if (f.hasCleared) 0f else 1f
                            renderEffect = painter.side(
                                f, side = 1f,
                                lens = (acting || (live && f.gravity > 0f)) && !f.hasCleared,
                                blurred = acting && !f.hasCleared,
                            )?.asComposeRenderEffect()
                        }
                        .drawBehind { drawImage(from) },
                )
            }
            // The colour on the front and the edges, and the sparks over it.
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val f = front() ?: return@drawBehind
                    if (progress.value <= 0f || !f.live) return@drawBehind
                    drawRect(ShaderBrush(painter.band(f)))
                    for (dot in painter.dots) {
                        val a = dot.opacity(f).coerceIn(0f, 1f)
                        if (a <= 0f) continue
                        val (x, y) = dot.point(f)
                        drawCircle(Color.White.copy(alpha = a), radius = dot.size / 2 * density, center = Offset(x * density, y * density))
                    }
                },
            )
        }
    }
}

/** Seconds after the launch at which the front has uncovered the top of the screen's content. */
private const val REVEAL_AFTER = 0.2f

/** The blur radius of the first blurred copy, dp; the second has the style's full radius. */
private const val NEAR_BLUR = 7f

/**
 * The shaders and the effects built from them. Each use has its own shader, as an effect keeps the
 * uniforms its shader had when it was made.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class WavePainter(private val style: WaveStyle, private val density: Float, private val cornerRadius: Float) {
    private val lens = arrayOf(RuntimeShader(WaveShaders.LENS), RuntimeShader(WaveShaders.LENS))
    private val near = arrayOf(RuntimeShader(WaveShaders.BLUR_MASK), RuntimeShader(WaveShaders.BLUR_MASK))
    private val far = arrayOf(RuntimeShader(WaveShaders.BLUR_MASK), RuntimeShader(WaveShaders.BLUR_MASK))
    private val bandShader = RuntimeShader(WaveShaders.BAND)
    val dots = List(style.particleCount) { WaveDot(it, style) }

    init {
        for (s in lens) {
            s.setFloatUniform("amplitude", style.amplitude)
            s.setFloatUniform("lensWidth", style.lensWidth)
            s.setFloatUniform("aberration", style.aberration)
        }
        lens[0].setFloatUniform("side", 1f)
        lens[1].setFloatUniform("side", -1f)
        for ((s, radius, from) in listOf(Triple(near, NEAR_BLUR, 0f), Triple(far, style.blur, NEAR_BLUR))) {
            for (shader in s) {
                shader.setFloatUniform("blur", style.blur)
                shader.setFloatUniform("ahead", style.blurAhead)
                shader.setFloatUniform("behind", style.blurBehind)
                shader.setFloatUniform("fromR", from)
                shader.setFloatUniform("toR", radius)
            }
        }
        with(bandShader) {
            setFloatUniform("pal", style.palette.oklab)
            setFloatUniform("lead", style.lead)
            setFloatUniform("tail", style.tail)
            setFloatUniform("strength", style.strength)
            setFloatUniform("rimWidth", style.rimWidth)
            setFloatUniform("rimSoftness", style.rimSoftness)
            setFloatUniform("cornerRadius", cornerRadius)
            setFloatUniform("unevenness", style.rimUnevenness)
            setFloatUniform("pulse", style.rimPulse)
            setFloatUniform("hairWidth", style.hairlineWidth)
            setFloatUniform("hairOpacity", style.hairlineOpacity)
            setFloatUniform("hairWhite", style.hairlineWhite)
        }
    }

    private fun RuntimeShader.field(f: WaveFront) {
        setFloatUniform("f0", f.originX, f.originY, f.spine, f.radius)
        setFloatUniform("f1", f.squash, f.time, f.energy, f.swell)
        setFloatUniform("f2", f.size.width, f.size.height, f.gravity, f.glow)
        setFloatUniform("f3", f.clearRadius, f.rim, if (f.launched) 1f else 0f, f.hairClearRadius)
        setFloatUniform("dens", density)
    }

    /**
     * One side of the transition: the content, its two blurred copies over it each showing only where the
     * blur should be that strong, all through the swell. side +1 is the outgoing screen, -1 the incoming.
     */
    fun side(f: WaveFront, side: Float, lens: Boolean, blurred: Boolean): RenderEffect? {
        if (!lens) return null
        val i = if (side > 0f) 0 else 1
        val swell = this.lens[i].apply { field(f) }.let { RenderEffect.createRuntimeShaderEffect(it, "content") }
        if (!blurred) return swell
        val sharp = RenderEffect.createOffsetEffect(0f, 0f)
        val stack = RenderEffect.createBlendModeEffect(
            RenderEffect.createBlendModeEffect(sharp, copy(near[i], NEAR_BLUR, f), BlendMode.SRC_OVER),
            copy(far[i], style.blur, f),
            BlendMode.SRC_OVER,
        )
        return RenderEffect.createChainEffect(swell, stack)
    }

    private fun copy(mask: RuntimeShader, radius: Float, f: WaveFront): RenderEffect {
        mask.field(f)
        val px = radius * density
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(mask, "content"),
            RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP),
        )
    }

    fun band(f: WaveFront): Shader = bandShader.apply {
        field(f)
        setFloatUniform("lumps", rimLumps(f.time, f.size, cornerRadius, style.rimPulse))
    }
}

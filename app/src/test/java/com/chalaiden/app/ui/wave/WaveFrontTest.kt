package com.chalaiden.app.ui.wave

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveFrontTest {
    private val style = WaveStyle()
    private val screen = Size(400f, 860f)
    private val button = Rect(24f, 732f, 376f, 788f)

    private fun at(seconds: Float) = WaveFront(seconds / style.duration, button, screen, style)

    @Test fun lastsAsLongAsItsSlowestPart() {
        // The hairline's linger and fade outlast the swell, the rim and the dots.
        assertEquals(0.5f + (-0.35f + 0.47f + 3f), style.duration, 1e-4f)
    }

    @Test fun startsAsTheButtonsOwnOutline() {
        val f = at(0f)
        assertEquals(button.center.x, f.originX)
        assertEquals(button.center.y, f.originY)
        assertEquals(176f - 28f, f.spine, 1e-3f) // a pill: half the width less the round end
        assertFalse(f.launched)
        assertEquals(0f, f.energy)
    }

    @Test fun pullsInBeforeItLeaves() {
        val f = at(style.anticipation * 0.9f)
        assertFalse(f.launched)
        assertTrue(f.gravity > 0f)
        assertTrue("the ring of colour falls in onto the outline", f.radius > 28f && f.radius < 28f + style.inhale)
    }

    @Test fun crossesTheScreenAndClearsIt() {
        val f = at(style.anticipation + style.travel * 0.6f)
        assertTrue(f.launched)
        assertTrue(f.hasCleared)
        assertEquals("the pill has opened into an arc", 0f, f.spine, 1e-3f)
        assertEquals("let go of the pull", 0f, f.gravity, 1e-4f)
    }

    @Test fun leavesNothingAtTheEnd() {
        val f = WaveFront(1f, button, screen, style)
        assertEquals(0f, f.energy, 1e-4f)
        assertEquals(0f, f.glow, 1e-4f)
        assertEquals(0f, f.rim, 1e-4f)
        assertFalse(f.live)
    }

    @Test fun neverMovesBackward() {
        var last = 0f
        for (i in 1..200) {
            val r = WaveFront(i / 200f, button, screen, style).radius
            if (WaveFront(i / 200f, button, screen, style).launched) assertTrue(r >= last - 1e-3f)
            last = r
        }
    }

    @Test fun easingKeepsItsEnds() {
        assertEquals(0f, style.easing(0f))
        assertEquals(1f, style.easing(1f))
        assertTrue("leaves at full speed", style.easing(0.1f) > 0.3f)
    }

    @Test fun paletteHasEveryColourAndBothFringes() {
        assertEquals((7 + 2) * 3, style.palette.oklab.size)
        // White would be L = 1; the yellow is bright but not white.
        assertTrue(style.palette.oklab[0] in 0.8f..0.95f)
    }
}

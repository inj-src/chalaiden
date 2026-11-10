package com.chalaiden.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SteadyTest {
    private val n = "01745606306"
    private val misread = "01714560163"

    /** Feeds [read] every 100 ms from [from] to [to] inclusive; the first time a frame said "take it", or null. */
    private fun Steady.hold(read: String?, from: Long, to: Long): Long? =
        (from..to step 100).firstOrNull { feed(read, it) }

    @Test fun takenAfterTwoSecondsOfTheSameNumber() = assertEquals(2_000L, Steady().hold(n, 0, 3_000))

    @Test fun aDifferentReadStartsOver() {
        val s = Steady()
        assertEquals(null, s.hold(n, 0, 1_400))
        assertEquals(null, s.hold(misread, 1_500, 1_500))
        assertEquals(3_600L, s.hold(n, 1_600, 5_000))
    }

    @Test fun aDroppedFrameIsForgiven() {
        val s = Steady()
        assertEquals(null, s.hold(n, 0, 900))
        assertEquals(null, s.hold(null, 1_000, 1_500)) // 600 ms without it: within the grace
        assertEquals(2_000L, s.hold(n, 1_600, 3_000))
    }

    @Test fun losingTheNumberStartsOver() {
        val s = Steady()
        assertEquals(null, s.hold(n, 0, 900))
        assertEquals(null, s.hold(null, 1_000, 1_700)) // 800 ms without it: gone
        assertEquals(3_800L, s.hold(n, 1_800, 5_000))
    }
}

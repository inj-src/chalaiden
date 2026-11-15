package com.chalaiden.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SteadyTest {
    private val n = "01745606306"
    private val other = "01911223344"

    /** Feeds [read] every 100 ms from [from] to [to] inclusive; when a frame first said "take it", and what. */
    private fun Steady.hold(read: (Long) -> String?, from: Long, to: Long): Pair<Long, String>? =
        (from..to step 100).firstNotNullOfOrNull { t -> feed(read(t), t)?.let { t to it } }

    private fun Steady.hold(read: String?, from: Long, to: Long) = hold({ read }, from, to)

    @Test fun takenAfterTwoSecondsOfTheSameNumber() = assertEquals(2_000L to n, Steady().hold(n, 0, 3_000))

    /** What the scanner really sees: most frames right, some with one digit off, never the same one twice. */
    @Test fun aFlickeringReadIsStillTakenAndVotedRight() {
        val flicker = listOf(n, "01745608306", n, "01746606306", n, "01745606366", n, "01715606306")
        val taken = Steady().hold({ t -> flicker[(t / 100 % flicker.size).toInt()] }, 0, 3_000)
        assertEquals(2_000L to n, taken)
    }

    @Test fun theVoteFixesDigitsNoSingleFrameGotRight() {
        // Every frame has one digit wrong, each in a different place.
        val reads = listOf("01745606300", "01745606356", "01745600306", "01745636306", "01748606306")
        assertEquals(n, Steady.consensus(reads))
    }

    @Test fun twoNumbersInTurnAreNeverSplicedTogether() {
        val taken = Steady().hold({ t -> if (t / 100 % 2 == 0L) n else other }, 0, 4_000)
        assertEquals(null, taken)
    }

    @Test fun panningToAnotherNumberTakesTheNewOne() {
        val s = Steady()
        assertEquals(null, s.hold(other, 0, 1_000))
        // Still a number in view, but the new one must hold a full 2 s of its own before it is taken.
        assertEquals(3_100L to n, s.hold(n, 1_100, 5_000))
    }

    @Test fun aDroppedFrameIsForgiven() {
        val s = Steady()
        assertEquals(null, s.hold(n, 0, 900))
        assertEquals(null, s.hold(null, 1_000, 1_500)) // 600 ms without it: within the grace
        assertEquals(2_000L to n, s.hold(n, 1_600, 3_000))
    }

    @Test fun losingTheNumberStartsOver() {
        val s = Steady()
        assertEquals(null, s.hold(n, 0, 900))
        assertEquals(null, s.hold(null, 1_000, 1_700)) // 800 ms without it: gone
        assertEquals(3_800L to n, s.hold(n, 1_800, 5_000))
    }
}

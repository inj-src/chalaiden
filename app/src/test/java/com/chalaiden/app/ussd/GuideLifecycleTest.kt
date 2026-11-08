package com.chalaiden.app.ussd

import com.chalaiden.app.ussd.GuideSession.End
import com.chalaiden.app.ussd.GuideSession.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLifecycleTest {
    private val pay = Flows.payment("01712345678", "250", "rice")
    private fun state(index: Int, old: Boolean = false, pinAt: Long? = null) =
        GuideSession.State(pay, Guide.steps(pay, oldSession = old), index = index, pinAt = pinAt)

    @Test fun cancelAtTheFirstMenuLeavesNothing() {
        assertFalse(Lifecycle.leftOpen(state(0), End.Gone, at = 1_000))
    }

    @Test fun cancelAfterAReplyLeavesTheSessionOpen() {
        assertTrue(Lifecycle.leftOpen(state(2), End.Gone, at = 1_000))
    }

    @Test fun theNoToAnOldSessionDoesNotCountAsAReply() {
        assertFalse(Lifecycle.leftOpen(state(0, old = true), End.Gone, at = 1_000))
        assertFalse(Lifecycle.leftOpen(state(1, old = true), End.Gone, at = 1_000))
        assertTrue(Lifecycle.leftOpen(state(2, old = true), End.Gone, at = 1_000))
    }

    @Test fun quickCloseAtThePinIsACancelSlowOneIsAPayment() {
        val pin = Guide.steps(pay).lastIndex
        assertTrue(Lifecycle.leftOpen(state(pin, pinAt = 10_000), End.Gone, at = 13_000))
        assertFalse(Lifecycle.leftOpen(state(pin, pinAt = 10_000), End.Gone, at = 40_000))
    }

    @Test fun doneAndCloseAssumeNothing() {
        assertFalse(Lifecycle.leftOpen(state(2), End.Done, at = 1_000))
        assertFalse(Lifecycle.leftOpen(state(2), End.Closed, at = 1_000))
    }

    @Test fun openSessionsExpire() {
        assertFalse(Lifecycle.stillOpen(0, 5_000))
        assertTrue(Lifecycle.stillOpen(1_000, 1_000 + Lifecycle.OPEN_MILLIS - 1))
        assertFalse(Lifecycle.stillOpen(1_000, 1_000 + Lifecycle.OPEN_MILLIS))
    }

    @Test fun oldSessionGetsANoFirstThenTheMenu() {
        val steps = Guide.steps(Flows.sendMoney("01712345678", "250", null), oldSession = true)
        assertEquals(GuideStep.Kind.Resume, steps[0].kind)
        val g = GuideSession.State(Flows.sendMoney("01712345678", "250", null), steps)
        // Send Money's first menu reply is 1, which means Yes on bKash's question: the No must come first.
        assertEquals(KeyMove.Type("2", 0), KeyboardFill.decide(g, inPhone = true, empty = true))
        assertEquals(KeyMove.Type("1", 1), KeyboardFill.decide(g.copy(filled = 0), inPhone = true, empty = true))
    }
}

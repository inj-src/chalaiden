package com.chalaiden.app.ussd

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFillTest {
    private fun guide(flow: UssdFlow, index: Int = 0, filled: Int? = null) =
        GuideSession.State(flow, Guide.steps(flow), index = index, filled = filled)

    private val pay = Flows.payment("01712345678", "250", "rice")

    @Test fun firstPromptGetsTheMenuNumber() {
        assertEquals(KeyMove.Type("4", 0), KeyboardFill.decide(guide(pay), inPhone = true, empty = true))
    }

    @Test fun nextEmptyBoxMeansBkashMovedOn() {
        assertEquals(KeyMove.Type("01712345678", 1), KeyboardFill.decide(guide(pay, 0, filled = 0), inPhone = true, empty = true))
        assertEquals(KeyMove.Type("250", 2), KeyboardFill.decide(guide(pay, 1, filled = 1), inPhone = true, empty = true))
    }

    @Test fun cardMovedOnByHandFillsThatStep() {
        assertEquals(KeyMove.Type("250", 2), KeyboardFill.decide(guide(pay, 2, filled = 0), inPhone = true, empty = true))
    }

    @Test fun boxWithTextIsLeftAlone() {
        assertEquals(KeyMove.None, KeyboardFill.decide(guide(pay, 1, filled = 1), inPhone = true, empty = false))
    }

    @Test fun pinIsNeverTypedTheUserGetsThePinPad() {
        assertEquals(KeyMove.PinPad(4), KeyboardFill.decide(guide(pay, 3, filled = 3), inPhone = true, empty = true))
        assertEquals(KeyMove.PinPad(4), KeyboardFill.decide(guide(pay, 4), inPhone = true, empty = true))
    }

    @Test fun aBoxAfterTheSentPinIsBkashsAnswerNotAnotherPin() {
        val bal = Flows.balance()
        assertEquals(KeyMove.Finished, KeyboardFill.decide(guide(bal, 2), inPhone = true, empty = true, pinSent = true))
        // Before the PIN went, the same box is still the PIN prompt.
        assertEquals(KeyMove.PinPad(2), KeyboardFill.decide(guide(bal, 2), inPhone = true, empty = true, pinSent = false))
    }

    @Test fun outsideTheUssdBoxOrWithoutAGuideHandsBack() {
        assertEquals(KeyMove.HandBack(), KeyboardFill.decide(guide(pay), inPhone = false, empty = true))
        assertEquals(KeyMove.HandBack(), KeyboardFill.decide(null, inPhone = true, empty = true))
    }

    @Test fun balanceWalksMyBkashThenCheckBalance() {
        val bal = Flows.balance()
        assertEquals(KeyMove.Type("9", 0), KeyboardFill.decide(guide(bal), inPhone = true, empty = true))
        assertEquals(KeyMove.Type("1", 1), KeyboardFill.decide(guide(bal, 0, filled = 0), inPhone = true, empty = true))
        assertEquals(KeyMove.PinPad(2), KeyboardFill.decide(guide(bal, 1, filled = 1), inPhone = true, empty = true))
    }

    @Test fun changedReplyAnsweredSomethingElseSoTheSameStepComesAgain() {
        // "Do you want to continue?" came first: the user replaced the typed 9 with 2.
        val bal = Flows.balance()
        val afterEdit = GuideSession.State(bal, Guide.steps(bal), index = 0, filled = 0, edited = true)
        assertEquals(KeyMove.Type("9", 0), KeyboardFill.decide(afterEdit, inPhone = true, empty = true))
    }
}

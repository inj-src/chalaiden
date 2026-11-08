package com.chalaiden.app.ussd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {
    @Test fun paymentStepsFollowTheFlow() {
        val steps = Guide.steps(Flows.payment("01712345678", "250", "rice"))
        assertEquals(
            listOf(GuideStep.Kind.Pick, GuideStep.Kind.Number, GuideStep.Kind.Amount, GuideStep.Kind.Reference, GuideStep.Kind.Pin),
            steps.map { it.kind },
        )
        assertEquals("Choose Payment", steps[0].title)
        assertTrue(steps[0].hint!!.startsWith("Usually 4"))
        assertEquals("Type the merchant number", steps[1].title)
        assertEquals("01712345678", steps[1].value)
        assertEquals("250", steps[2].value)
        assertEquals("rice", steps[3].value)
        assertNull(steps[3].hint)
        assertNull(steps[4].value)
        assertEquals("Enter your PIN", steps[4].title)
    }

    @Test fun referenceThatLooksLikeTheAmountStaysAReference() {
        val steps = Guide.steps(Flows.sendMoney("01712345678", "250", "250"))
        assertEquals(GuideStep.Kind.Amount, steps[2].kind)
        assertEquals(GuideStep.Kind.Reference, steps[3].kind)
        assertEquals("Type the receiver's number", steps[1].title)
    }

    @Test fun emptyReferenceExplainsThePlaceholder() {
        val ref = Guide.steps(Flows.payment("01712345678", "250", null))[3]
        assertEquals(Flows.DEFAULT_REFERENCE, ref.value)
        assertNotNull(ref.hint)
    }

    @Test fun cashOutAndBalance() {
        val cash = Guide.steps(Flows.cashOut("01712345678", "500"))
        assertEquals(listOf("Choose Cash Out", "Choose From Agent", "Type the agent number", "Type the amount", "Enter your PIN"), cash.map { it.title })
        val balance = Guide.steps(Flows.balance())
        assertEquals(listOf("Choose My bKash", "Choose Check Balance", "Enter your PIN"), balance.map { it.title })
    }

    @Test fun sessionMovesWithinBounds() {
        val flow = Flows.balance()
        GuideSession.start(flow)
        GuideSession.back()
        assertEquals(0, GuideSession.state.value!!.index)
        repeat(5) { GuideSession.next() }
        assertTrue(GuideSession.state.value!!.isLast)
        GuideSession.start(flow) // a new dial starts bKash's menu from the top
        assertEquals(0, GuideSession.state.value!!.index)
        GuideSession.go(2)
        GuideSession.end(GuideSession.End.Closed)
        assertNull(GuideSession.state.value)
    }
}

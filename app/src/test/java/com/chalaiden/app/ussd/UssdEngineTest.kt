package com.chalaiden.app.ussd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Strings captured from a real Grameenphone `*247#` session, 2026-11-02. */
class UssdEngineTest {
    private val menu = "bKash\n1 Send Money\n2 Send Money to Non-bKash User\n3 Mobile Recharge\n4 Payment\n" +
        "5 Cash Out\n6 Pay Bill\n7 Microfinance\n8 Download bKash App\n9 My bKash\n10 Reset PIN"
    private val paymentConfirm = "Payment.\nTo: 01783007004\nAmount: Tk 10\nFee: Tk 0.00\nTotal Amount: 10.0\n" +
        "Reference: test\nEnter Menu PIN to confirm:"

    private fun prompt(t: String) = Dialog.Prompt(t)

    @Test fun paymentWalksToPin() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"), clock = { 42L })
        assertEquals(Action.Reply("4"), e.onDialog(prompt(menu)))
        e.onDialog(Dialog.Progress)
        assertEquals(Action.Reply("01783007004"), e.onDialog(prompt("Enter Merchant bKash Account No:")))
        assertEquals(Action.Reply("10"), e.onDialog(prompt("Enter Amount:")))
        assertEquals(Action.Reply("test"), e.onDialog(prompt("Enter Reference:")))
        assertEquals(Action.None, e.onDialog(prompt(paymentConfirm)))
        val s = e.state as RunState.AwaitingPin
        assertEquals("01783007004", s.details.toMap()["To"])
        assertEquals("Tk 0.00", s.details.toMap()["Fee"])
        assertEquals(Action.Reply("12345"), e.submitPin("12345"))
    }

    @Test fun sendMoneyIgnoresPromoLinesAndPicksExactLabel() {
        val e = UssdEngine(Flows.sendMoney("01783007004", "50", null))
        assertEquals(Action.Reply("1"), e.onDialog(prompt(menu)))
        assertEquals(Action.Reply("01783007004"), e.onDialog(prompt("Enter Receiver bKash Account No:")))
        val promo = "FREE Send Money to 5 Priyo Numbers.\nDial *247# and select number 9 to find priyo Numbers.\nEnter Amount:"
        assertEquals(Action.Reply("50"), e.onDialog(prompt(promo)))
        assertEquals(Action.Reply(Flows.DEFAULT_REFERENCE), e.onDialog(prompt("FREE Send Money…\nEnter Reference:")))
    }

    @Test fun unexpectedPromptCancelsBeforePin() {
        val e = UssdEngine(Flows.payment("01783007004", "10", null))
        e.onDialog(prompt(menu))
        assertEquals(Action.Cancel, e.onDialog(prompt("Enter Amount:")))
        val outcome = (e.state as RunState.Finished).outcome as Outcome.Failed
        assertEquals(false, outcome.pinSent)
    }

    @Test fun repeatedEventsForSameDialogActOnce() {
        val e = UssdEngine(Flows.payment("01783007004", "10", null))
        assertEquals(Action.Reply("4"), e.onDialog(prompt(menu)))
        assertEquals(Action.None, e.onDialog(prompt(menu)))
    }

    @Test fun serviceUnavailableFailsWithoutPin() {
        val e = UssdEngine(Flows.balance())
        assertEquals(Action.Dismiss, e.onDialog(Dialog.Notice("Service is not available at this moment. Please try again later.")))
        assertTrue((e.state as RunState.Finished).outcome is Outcome.Failed)
    }

    @Test fun afterPinNothingIsAnswered() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        listOf(menu, "Enter Merchant bKash Account No:", "Enter Amount:", "Enter Reference:", paymentConfirm)
            .forEach { e.onDialog(prompt(it)) }
        e.submitPin("12345")
        assertEquals(Action.Cancel, e.onDialog(prompt("Some new question:")))
        assertTrue((e.state as RunState.Finished).outcome !is Outcome.Success)
    }

    @Test fun trxIdMeansSuccess() {
        val o = Outcomes.classify(FlowKind.Payment, "Payment Tk 10.00 to 01783007004 successful. TrxID BK12AB34CD at 02/11/2026")
        assertEquals("BK12AB34CD", (o as Outcome.Success).trxId)
    }
}

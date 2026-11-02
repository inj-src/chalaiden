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

    @Test fun resumeOfferIsDeclinedThenMenuIsUsed() {
        val e = UssdEngine(Flows.sendMoney("01400635085", "10", null))
        // Seen on device: an unfinished session makes the next *247# ask this first; "2" gives a fresh menu.
        assertEquals(Action.Reply("2"), e.onDialog(prompt("Do you want to continue?\n1 - Yes\n2 - No")))
        e.onDialog(Dialog.Progress)
        assertEquals(Action.Reply("1"), e.onDialog(prompt(menu)))
    }

    @Test fun unknownPromptInsteadOfMenuIsFlagged() {
        val e = UssdEngine(Flows.sendMoney("01400635085", "10", null))
        assertEquals(Action.Cancel, e.onDialog(prompt("Your request is being processed.\n1 - OK")))
        val outcome = (e.state as RunState.Finished).outcome as Outcome.Failed
        assertTrue(outcome.menuMissing)
    }

    @Test fun balanceReplyIsSuccess() {
        val o = Outcomes.classify(FlowKind.Balance, "bKash\nCurrent balance Tk 3,299.05. Available balance Tk 3,299.05.\n0. Main Menu")
        assertTrue(o is Outcome.Success)
        assertEquals(mapOf("current" to "3,299.05", "available" to "3,299.05"), Prompt.balances((o as Outcome.Success).message))
    }

    @Test fun trailingPromptAfterSessionIsClosedNotAnswered() {
        UssdSession.begin(Flows.balance())
        UssdSession.clear(now = 1_000L)
        val trailing = prompt("Do you want to continue?\n1 - Yes\n2 - No")
        assertEquals(Action.Cancel, UssdSession.onDialog(trailing, now = 5_000L))
        assertEquals(Action.Dismiss, UssdSession.onDialog(Dialog.Notice("Thank you"), now = 5_000L))
        assertEquals(Action.None, UssdSession.onDialog(trailing, now = 1_000L + UssdSession.TAIL_MILLIS))
    }

    @Test fun staleEarlierPromptIsIgnoredNotAMismatch() {
        // Seen on device: the "Enter Amount:" window lingers after we answered it.
        val e = UssdEngine(Flows.payment("01783007004", "10", null))
        e.onDialog(prompt(menu)); e.onDialog(Dialog.Progress)
        e.onDialog(prompt("Enter Merchant bKash Account No:")); e.onDialog(Dialog.Progress)
        assertEquals(Action.Reply("10"), e.onDialog(prompt("Enter Amount:")))
        e.onDialog(Dialog.Progress)
        assertEquals(Action.None, e.onDialog(prompt("Enter Amount:")))
        assertEquals(Action.Reply(Flows.DEFAULT_REFERENCE), e.onDialog(prompt("Enter Reference:")))
    }

    @Test fun stalledSessionIsCancelled() {
        UssdSession.begin(Flows.payment("01783007004", "10", null))
        assertEquals(Action.None, UssdSession.checkStall(now = 1_000L))
        assertEquals(Action.Cancel, UssdSession.checkStall(now = UssdSession.STALL_MILLIS + 5_000L + System.currentTimeMillis()))
        assertTrue((UssdSession.current.value!!.state as RunState.Finished).outcome is Outcome.Failed)
        UssdSession.clear()
    }

    @Test fun defaultReferenceIsRecognisedWhenEchoed() {
        assertTrue(Flows.isDefaultReference("ZQX7K"))
        assertTrue(Flows.isDefaultReference(" zqx7k "))
        assertTrue(!Flows.isDefaultReference("1"))
        assertEquals("ZQX7K", Flows.sendMoney("01400635085", "10", "  ").reference)
    }

    @Test fun referenceKeepsOnlyWhatBkashAccepts() {
        assertEquals("table 4", Flows.cleanReference("table <4>"))
        assertEquals("noref", Flows.cleanReference("no-ref"))
    }
}

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
        assertEquals("12345", (e.submitPin("12345") as Action.Reply).text)
    }

    private fun walkPayment(e: UssdEngine) {
        e.onDialog(prompt(menu))
        e.onDialog(prompt("Enter Merchant bKash Account No:"))
        e.onDialog(prompt("Enter Amount:"))
        e.onDialog(prompt("Enter Reference:"))
    }

    @Test fun pinEnteredUpFrontGoesInWhenSummaryMatches() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        assertEquals(Action.None, e.presetPin("12345"))
        walkPayment(e)
        assertEquals("12345", (e.onDialog(prompt(paymentConfirm)) as Action.Reply).text)
        assertEquals(RunState.Confirming, e.state)
    }

    @Test fun pinEnteredAfterSummaryArrivedGoesInToo() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        walkPayment(e)
        assertEquals(Action.None, e.onDialog(prompt(paymentConfirm)))
        assertEquals("12345", (e.presetPin("12345") as Action.Reply).text)
    }

    @Test fun summaryWithAnotherAmountCancelsInsteadOfSendingPin() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.presetPin("12345")
        walkPayment(e)
        assertEquals(Action.Cancel, e.onDialog(prompt(paymentConfirm.replace("Amount: Tk 10", "Amount: Tk 100"))))
        assertEquals(false, ((e.state as RunState.Finished).outcome as Outcome.Failed).pinSent)
    }

    @Test fun pinReplyNamesTheSummaryItAnswers() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.presetPin("12345")
        walkPayment(e)
        assertEquals(paymentConfirm, (e.onDialog(prompt(paymentConfirm)) as Action.Reply).to)
    }

    @Test fun amountWithSeveralNumbersCancelsRatherThanMatchingTheFirst() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.presetPin("12345")
        walkPayment(e)
        assertEquals(Action.Cancel, e.onDialog(prompt(paymentConfirm.replace("Amount: Tk 10", "Amount: Tk 10 000.00"))))
    }

    @Test fun thousandsSeparatorStillMatches() {
        val e = UssdEngine(Flows.payment("01783007004", "1000", "test"))
        e.presetPin("12345")
        walkPayment(e)
        val confirm = paymentConfirm.replace("Amount: Tk 10", "Amount: Tk 1,000.00")
        assertEquals("12345", (e.onDialog(prompt(confirm)) as Action.Reply).text)
    }

    @Test fun unreadableFeeIsShownNotTakenAsFree() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.presetPin("12345")
        walkPayment(e)
        assertEquals(Action.None, e.onDialog(prompt(paymentConfirm.replace("Fee: Tk 0.00", "Fee: see app"))))
        assertEquals("see app", (e.state as RunState.AwaitingPin).feeToConfirm)
    }

    @Test fun summaryWithAnotherRecipientCancelsInsteadOfSendingPin() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.presetPin("12345")
        walkPayment(e)
        assertEquals(Action.Cancel, e.onDialog(prompt(paymentConfirm.replace("To: 01783007004", "To: 01700000000"))))
    }

    @Test fun feeTheUserHasNotSeenWaitsForGoAhead() {
        val e = UssdEngine(Flows.sendMoney("01783007004", "500", null))
        e.presetPin("12345")
        e.onDialog(prompt(menu))
        e.onDialog(prompt("Enter Receiver bKash Account No:"))
        e.onDialog(prompt("Enter Amount:"))
        e.onDialog(prompt("Enter Reference:"))
        val confirm = "Send Money\nTo: 01783007004\nAmount: Tk 500\nFee: Tk 5.00\nReference: ${Flows.DEFAULT_REFERENCE}\nEnter Menu PIN to confirm:"
        assertEquals(Action.None, e.onDialog(prompt(confirm)))
        assertEquals("Tk 5.00", (e.state as RunState.AwaitingPin).feeToConfirm)
        assertEquals("12345", (e.confirmFee() as Action.Reply).text)
        assertEquals(RunState.Confirming, e.state)
    }

    @Test fun cashOutWalksAgentMenu() {
        // Captured from a real Grameenphone session, 2026-11-05.
        val e = UssdEngine(Flows.cashOut("01783007004", "50"))
        e.presetPin("12345")
        assertEquals(Action.Reply("5"), e.onDialog(prompt(menu)))
        val sub = "bKash\n1 From Agent\n2 From ATM\n3 Priyo Agent Numbers\n4 Set up to 2 Priyo Agent Numbers to Cash Out at 13.95 Tk/thousand\n# NEXT"
        assertEquals(Action.Reply("1"), e.onDialog(prompt(sub)))
        assertEquals(Action.Reply("01783007004"), e.onDialog(prompt(
            "Cash out at 13.95 Tk/thousand by adding up to 2 Priyo Agent numbers.\nDial *247# select option 9 then select option 4\nEnter Agent bKash Account No:")))
        assertEquals(Action.Reply("50"), e.onDialog(prompt(
            "Cash Out at 13.95 Tk/thousand from 2 Priyo Agent numbers up to 50,000 Tk every month.\nDial *247# select option 9 then select option 4\nEnter Amount:")))
        val confirm = "Cash Out at 13.95 Tk/thousand from 2 Priyo Agent numbers\nCash Out\nTo: 01783007004\nAmount: Tk 50\nEnter Menu PIN to confirm:"
        assertEquals("12345", (e.onDialog(prompt(confirm)) as Action.Reply).text)
    }

    @Test fun lingeringPinPromptAfterSubmitIsNotTheReply() {
        val e = UssdEngine(Flows.payment("01783007004", "10", "test"))
        e.onDialog(prompt(menu))
        e.onDialog(prompt("Enter Merchant bKash Account No:"))
        e.onDialog(prompt("Enter Amount:"))
        e.onDialog(prompt("Enter Reference:"))
        e.onDialog(prompt(paymentConfirm))
        e.submitPin("12345")
        // The PIN dialog can still be on screen after a progress frame; it is not bKash's answer.
        e.onDialog(Dialog.Progress)
        assertEquals(Action.None, e.onDialog(prompt(paymentConfirm)))
        assertEquals(RunState.Confirming, e.state)
        val reply = "Payment Tk 10.00 to 01783007004 successful. Fee Tk 0.00. Balance Tk 5.00. TrxID ABC123XYZ at 05/11/2026 10:00"
        e.onDialog(Dialog.Notice(reply))
        assertTrue(e.state is RunState.Finished)
    }

    @Test fun referenceKeepsOnlyCharactersInputCanType() {
        assertEquals("table 4", Flows.cleanReference("টেবিল table 4"))
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

    /** A session warmed up from the PIN screen (first digit typed), walked to bKash's summary. */
    private fun warmToSummary(): UssdFlow {
        val flow = Flows.payment("01783007004", "10", "test")
        UssdSession.prepare(flow)
        UssdSession.begin(flow)
        listOf(menu, "Enter Merchant bKash Account No:", "Enter Amount:", "Enter Reference:", paymentConfirm)
            .forEach { UssdSession.onDialog(prompt(it)) }
        assertTrue(UssdSession.current.value!!.state is RunState.AwaitingPin)
        return flow
    }

    @Test fun warmMenuBkashDropsWhileTypingQuietlyRearms() {
        warmToSummary()
        assertEquals(Action.Dismiss, UssdSession.onDialog(Dialog.Notice("Session timed out")))
        val snap = UssdSession.current.value!!
        assertEquals(RunState.Ready, snap.state)
        assertTrue(!snap.confirmed && !UssdSession.isActive)
        UssdSession.clear()
    }

    @Test fun warmMenuIsClosedBeforeBkashWouldDropIt() {
        warmToSummary()
        val late = System.currentTimeMillis() + UssdSession.WARM_EXPIRY_MILLIS + 1_000L
        assertEquals(Action.Cancel, UssdSession.checkStall(now = late))
        assertEquals(RunState.Ready, UssdSession.current.value!!.state)
        UssdSession.clear()
    }

    @Test fun warmMenuFailingBeforeSummaryIsStillShown() {
        val flow = Flows.payment("01783007004", "10", "test")
        UssdSession.prepare(flow)
        UssdSession.begin(flow)
        UssdSession.onDialog(prompt(menu))
        assertEquals(Action.Dismiss, UssdSession.onDialog(Dialog.Notice("Invalid merchant number")))
        assertTrue(UssdSession.current.value!!.state is RunState.Finished)
        UssdSession.clear()
    }

    @Test fun confirmedSessionLostIsNotRearmed() {
        warmToSummary()
        UssdSession.confirm("12345")
        assertEquals(RunState.Confirming, UssdSession.current.value!!.state)
        UssdSession.onSessionLost()
        val state = UssdSession.current.value!!.state as RunState.Finished
        assertTrue(state.outcome is Outcome.Unknown)
        UssdSession.clear()
    }

    @Test fun defaultReferenceIsRecognisedWhenEchoed() {
        assertTrue(Flows.isDefaultReference("ChalaiDen"))
        assertTrue(Flows.isDefaultReference(" CHALAIDEN "))
        assertTrue(!Flows.isDefaultReference("1"))
        assertEquals("ChalaiDen", Flows.sendMoney("01400635085", "10", "  ").reference)
    }

    @Test fun referenceKeepsOnlyWhatBkashAccepts() {
        assertEquals("table 4", Flows.cleanReference("table <4>"))
        assertEquals("noref", Flows.cleanReference("no-ref"))
    }
}

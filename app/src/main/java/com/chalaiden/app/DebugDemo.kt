package com.chalaiden.app

import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdFlow

/**
 * Debug builds only: made-up screens for checking layouts without dialling bKash. Nothing here reaches
 * the USSD driver. Opened with
 * `adb shell am start -n com.chalaiden.app/.MainActivity --es demo <state> [--es kind send] [--ez worst true]`.
 *
 * States: pin, working, fee, confirming (the run screen); success, failed, failed_pin, busy, expired,
 * unknown, cancelled (the result screen); receipt (writes the shared receipt to cache/receipts and stops).
 * `worst` swaps the demo values for the longest ones each field can hold.
 */
internal object DebugDemo {
    sealed interface Target {
        data class Run(val flow: UssdFlow, val state: RunState, val confirmed: Boolean) : Target
        data class Result(val flow: UssdFlow, val outcome: Outcome) : Target
        data class Receipt(val flow: UssdFlow, val outcome: Outcome.Success) : Target
    }

    fun target(state: String, kind: String?, worst: Boolean): Target? {
        val flowKind = when (kind) {
            "send" -> FlowKind.SendMoney
            "cashout" -> FlowKind.CashOut
            "balance" -> FlowKind.Balance
            else -> FlowKind.Payment
        }
        val flow = flow(flowKind, worst)
        val trx = if (worst) "BKH9X7Q2ZLMW" else "CK41BA7Q2Z"
        val success = Outcome.Success(
            if (flowKind == FlowKind.Balance) balanceMessage(worst) else successMessage(worst, trx),
            trx.takeIf { flowKind != FlowKind.Balance },
        )
        return when (state) {
            "pin" -> Target.Run(flow, RunState.Ready, confirmed = false)
            "working" -> Target.Run(flow, RunState.Working(2, "Entering the number"), confirmed = true)
            "fee" -> Target.Run(flow, RunState.AwaitingPin(feeDetails(flow, worst), System.currentTimeMillis(), feeToConfirm = if (worst) "Tk 18,499.99" else "Tk 9.25"), confirmed = true)
            "confirming" -> Target.Run(flow, RunState.AwaitingPin(emptyList(), System.currentTimeMillis()), confirmed = true)
            "success" -> Target.Result(flow, success)
            "failed" -> Target.Result(flow, Outcome.Failed(failMessage(worst), pinSent = false))
            "failed_pin" -> Target.Result(flow, Outcome.Failed(failMessage(worst), pinSent = true))
            "busy" -> Target.Result(flow, Outcome.Failed("Please select a valid option", pinSent = false, menuMissing = true))
            "expired" -> Target.Result(flow, Outcome.Failed("Connection problem or invalid MMI code.", pinSent = false))
            "unknown" -> Target.Result(flow, Outcome.Unknown(if (worst) LONG_TEXT else "The session ended before bKash replied."))
            "cancelled" -> Target.Result(flow, Outcome.Cancelled)
            "receipt" -> Target.Receipt(flow, success)
            else -> null
        }
    }

    private fun flow(kind: FlowKind, worst: Boolean): UssdFlow {
        val number = if (worst) "01999999999" else "01302277488"
        val amount = if (worst) "999999" else "1250"
        val reference = if (worst) "Wwwwwwwwww Wwwwwwwww" else null
        return when (kind) {
            FlowKind.Payment -> Flows.payment(number, amount, reference).copy(groups = if (worst) null else listOf(5, 3, 3))
            FlowKind.SendMoney -> Flows.sendMoney(number, amount, reference)
            FlowKind.CashOut -> Flows.cashOut(number, amount)
            FlowKind.Balance -> Flows.balance()
        }
    }

    private fun feeDetails(flow: UssdFlow, worst: Boolean): List<Pair<String, String>> = buildList {
        add("Amount" to "Tk ${"%,d".format(flow.amount?.toLongOrNull() ?: 0)}")
        add("Charge" to if (worst) "Tk 18,499.99" else "Tk 9.25")
        add("Total" to if (worst) "Tk 1,018,498.99" else "Tk 1,259.25")
        flow.recipient?.let { add("Agent" to it) }
        if (worst) add("Reference" to (flow.reference ?: ""))
        add("Reference" to Flows.DEFAULT_REFERENCE) // hidden: the placeholder
    }

    private fun successMessage(worst: Boolean, trx: String) =
        if (worst) "$LONG_TEXT TrxID $trx at 08/11/2026 23:59" else "Payment Tk 1,250.00 to 01302277488 successful. TrxID $trx"

    private fun balanceMessage(worst: Boolean) =
        if (worst) "Current balance Tk 1,234,567.89 Available balance Tk 1,000,000.00" else "Current balance Tk 3,299.05"

    private fun failMessage(worst: Boolean) =
        if (worst) LONG_TEXT else "Insufficient balance."

    private const val LONG_TEXT =
        "Dear Customer, your transaction could not be processed at this moment because the receiver account has " +
            "reached its monthly limit. Please try again later or call 16247 for support. Ref:XQ-1192837465."
}

package com.chalaiden.app.ussd

/** What the accessibility service sees in the phone app's USSD window. */
sealed interface Dialog {
    /** A message with a reply box. */
    data class Prompt(val text: String) : Dialog

    /** A message with only OK/Cancel, so the session has ended. */
    data class Notice(val text: String) : Dialog

    /** "Running USSD code…" between exchanges. */
    data object Progress : Dialog
}

sealed interface Action {
    data class Reply(val text: String) : Action
    data object Cancel : Action
    data object Dismiss : Action
    data object None : Action
}

sealed interface Outcome {
    data class Success(val message: String, val trxId: String?) : Outcome
    /** [menuMissing]: bKash answered with something other than its main menu, e.g. a leftover prompt. */
    data class Failed(val message: String, val pinSent: Boolean, val menuMissing: Boolean = false) : Outcome
    data class Unknown(val message: String) : Outcome
    data object Cancelled : Outcome
}

sealed interface RunState {
    data class Working(val stepIndex: Int, val label: String) : RunState
    data class AwaitingPin(val details: List<Pair<String, String>>, val sinceMillis: Long) : RunState
    data object Confirming : RunState
    data class Finished(val outcome: Outcome) : RunState
}

/**
 * Drives one `*247#` session. Pure logic: it is fed dialogs and returns what to do with them.
 *
 * Safety rule: before the PIN is sent any mismatch cancels the session (no money can move).
 * After the PIN is sent nothing is ever answered automatically again.
 */
class UssdEngine(val flow: UssdFlow, private val clock: () -> Long = System::currentTimeMillis) {
    var state: RunState = RunState.Working(0, "Opening bKash menu")
        private set

    private var lastHandled: Dialog? = null

    fun onDialog(dialog: Dialog): Action {
        if (dialog is Dialog.Progress) {
            lastHandled = null
            return Action.None
        }
        // Content-changed events repeat the same dialog many times; act once per dialog.
        if (dialog == lastHandled) return Action.None
        lastHandled = dialog

        return when (val s = state) {
            is RunState.Working -> advance(s.stepIndex, dialog)
            is RunState.AwaitingPin -> {
                // A new dialog while we wait for the PIN means bKash dropped the session.
                finish(Outcome.Failed(textOf(dialog).ifBlank { "The bKash session timed out." }, pinSent = false))
                if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
            }
            RunState.Confirming -> {
                finish(Outcomes.classify(flow.kind, textOf(dialog)))
                if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
            }
            is RunState.Finished -> if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
        }
    }

    fun submitPin(pin: String): Action {
        if (state !is RunState.AwaitingPin) return Action.None
        state = RunState.Confirming
        return Action.Reply(pin)
    }

    fun cancel(): Action {
        val pinSent = state == RunState.Confirming
        if (state is RunState.Finished) return Action.None
        finish(if (pinSent) Outcome.Unknown("You closed Chalaiden after the PIN was sent.") else Outcome.Cancelled)
        return Action.Cancel
    }

    /** The phone app's window went away without a final message. */
    fun onSessionLost() {
        when (state) {
            is RunState.Working, is RunState.AwaitingPin ->
                finish(Outcome.Failed("The bKash session ended before payment. Nothing was charged.", pinSent = false))
            RunState.Confirming ->
                finish(Outcome.Unknown("bKash didn't reply after the PIN."))
            is RunState.Finished -> Unit
        }
    }

    private fun advance(index: Int, dialog: Dialog): Action {
        val text = textOf(dialog)
        if (dialog !is Dialog.Prompt) {
            finish(Outcome.Failed(text, pinSent = false))
            return Action.Dismiss
        }
        return when (val step = flow.steps[index]) {
            is Step.Pick -> {
                val option = Prompt.optionFor(text, step.label) ?: return mismatch(text, menuMissing = index == 0)
                moveTo(index + 1)
                Action.Reply(option)
            }
            is Step.Answer -> {
                if (!step.prompt.containsMatchIn(Prompt.lastLine(text))) return mismatch(text)
                moveTo(index + 1)
                Action.Reply(step.value)
            }
            Step.Pin -> {
                if (!Prompt.isPinPrompt(text)) return mismatch(text)
                state = RunState.AwaitingPin(Prompt.details(text), clock())
                Action.None
            }
        }
    }

    private fun moveTo(index: Int) {
        val label = when (val step = flow.steps[index]) {
            is Step.Pick -> "Choosing ${step.label}"
            is Step.Answer -> step.describe
            Step.Pin -> "Waiting for bKash to confirm"
        }
        state = RunState.Working(index, label)
    }

    private fun mismatch(text: String, menuMissing: Boolean = false): Action {
        finish(Outcome.Failed(text, pinSent = false, menuMissing = menuMissing))
        return Action.Cancel
    }

    private fun finish(outcome: Outcome) {
        state = RunState.Finished(outcome)
    }

    private fun textOf(dialog: Dialog) = when (dialog) {
        is Dialog.Prompt -> dialog.text
        is Dialog.Notice -> dialog.text
        Dialog.Progress -> ""
    }
}

object Outcomes {
    /**
     * Reads bKash's reply after the PIN was sent.
     * Misreading a failure as success is the worst case, so anything unrecognised is Unknown.
     */
    fun classify(kind: FlowKind, text: String): Outcome {
        val trx = Prompt.trxId(text)
        if (trx != null) return Outcome.Success(text, trx)
        if (kind == FlowKind.Balance && Regex("""balance""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("""Tk\.?\s*[\d,]+""").containsMatchIn(text)
        ) return Outcome.Success(text, null)

        // TODO(you): recognise bKash's failure replies (wrong PIN, low balance, limits…)
        //  and return Outcome.Failed(text, pinSent = true) for them.

        return Outcome.Unknown(text)
    }
}

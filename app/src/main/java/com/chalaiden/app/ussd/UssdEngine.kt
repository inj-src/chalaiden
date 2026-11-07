package com.chalaiden.app.ussd

/** What the driver reads from the phone app's USSD window. */
sealed interface Dialog {
    /** A message with a reply box. */
    data class Prompt(val text: String) : Dialog

    /** A message with only OK/Cancel, so the session has ended. */
    data class Notice(val text: String) : Dialog

    /** "Running USSD code…" between exchanges. */
    data object Progress : Dialog
}

sealed interface Action {
    /**
     * [to]: the exact prompt this answers. Set for the PIN, which goes in from the PIN pad some time
     * after its summary was checked; the driver refuses to type it unless that prompt is still showing.
     */
    data class Reply(val text: String, val to: String? = null) : Action
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
    /** Details and PIN entry on Chalaiden's screen; nothing dialled yet. */
    data object Ready : RunState
    data class Working(val stepIndex: Int, val label: String) : RunState

    /**
     * bKash's confirmation is up and waits for the PIN. [feeToConfirm]: the PIN was entered up front,
     * but bKash added a fee the user hasn't seen, so it waits for one more go-ahead.
     */
    data class AwaitingPin(
        val details: List<Pair<String, String>>,
        val sinceMillis: Long,
        val feeToConfirm: String? = null,
    ) : RunState
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

    /** The confirmation prompt the PIN answered; it can linger on screen after the PIN is sent. */
    private var pinPrompt: String? = null
    private var resumeDeclines = 0

    /** Entered before bKash asked for it; sent only once bKash's summary matches the flow. Wiped once used. */
    private var pin: String? = null

    /** Prompts already answered. OxygenOS keeps the previous dialog around for a moment; seeing it again is not a new question. */
    private val answered = mutableSetOf<String>()

    /** When the state last moved, for spotting a stalled session. */
    var lastChangeAt: Long = clock()
        private set

    fun onDialog(dialog: Dialog): Action {
        if (dialog is Dialog.Progress) {
            lastHandled = null
            return Action.None
        }
        // Content-changed events repeat the same dialog many times; act once per dialog.
        if (dialog == lastHandled) return Action.None
        lastHandled = dialog

        if (dialog is Dialog.Prompt && dialog.text in answered && state is RunState.Working) return Action.None

        return when (val s = state) {
            is RunState.Working -> advance(s.stepIndex, dialog).also { if (it is Action.Reply) answered += dialog.textOrEmpty() }
            is RunState.AwaitingPin -> {
                // A new dialog while we wait for the PIN means bKash dropped the session.
                finish(Outcome.Failed(textOf(dialog).ifBlank { "The bKash session timed out." }, pinSent = false))
                if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
            }
            RunState.Confirming -> {
                if (dialog is Dialog.Prompt && dialog.text == pinPrompt) return Action.None
                finish(Outcomes.classify(flow.kind, textOf(dialog)))
                if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
            }
            is RunState.Finished -> if (dialog is Dialog.Prompt) Action.Cancel else Action.Dismiss
            RunState.Ready -> Action.None // an engine starts at Working; Ready is the session's
        }
    }

    fun submitPin(pin: String): Action {
        if (state !is RunState.AwaitingPin) return Action.None
        this.pin = null
        lastChangeAt = clock()
        state = RunState.Confirming
        return Action.Reply(pin, to = pinPrompt)
    }

    /**
     * The PIN, entered before the menu is walked. It goes in by itself when bKash's summary shows the
     * same recipient and amount as the flow; a fee the user hasn't seen holds it back for a go-ahead.
     */
    fun presetPin(pin: String): Action {
        if (state is RunState.Finished || state == RunState.Confirming) return Action.None
        this.pin = pin
        val s = state as? RunState.AwaitingPin ?: return Action.None
        if (s.feeToConfirm != null) return Action.None
        return pinPrompt?.let(::usePresetPin) ?: Action.None
    }

    /** The user saw bKash's fee and still wants to go ahead with the PIN entered earlier. */
    fun confirmFee(): Action {
        val s = state as? RunState.AwaitingPin ?: return Action.None
        val p = pin ?: return Action.None
        if (s.feeToConfirm == null) return Action.None
        return submitPin(p)
    }

    fun cancel(): Action {
        pin = null
        val pinSent = state == RunState.Confirming
        if (state is RunState.Finished) return Action.None
        finish(if (pinSent) Outcome.Unknown("You closed Chalaiden after the PIN was sent.") else Outcome.Cancelled)
        return Action.Cancel
    }

    /** A reply (the PIN included) could not be typed because the USSD field was gone. Nothing reached bKash. */
    fun onReplyUndelivered() {
        if (state is RunState.Finished) return
        finish(Outcome.Failed("bKash closed the menu before Chalaiden could answer. Nothing was sent.", pinSent = false))
    }

    /** The phone app's window went away without a final message. */
    fun onSessionLost() {
        when (state) {
            is RunState.Working, is RunState.AwaitingPin ->
                finish(Outcome.Failed("The bKash session ended before payment. Nothing was charged.", pinSent = false))
            RunState.Confirming ->
                finish(Outcome.Unknown("bKash didn't reply after the PIN."))
            is RunState.Finished, RunState.Ready -> Unit
        }
    }

    private fun advance(index: Int, dialog: Dialog): Action {
        val text = textOf(dialog)
        if (dialog !is Dialog.Prompt) {
            finish(Outcome.Failed(text, pinSent = false))
            return Action.Dismiss
        }
        // bKash remembers a half-finished session and offers to resume it before the menu.
        // Always decline: resuming would land us at an unknown step of a money flow.
        if (index == 0 && Prompt.isResumeOffer(text) && resumeDeclines < 2) {
            val no = Prompt.optionFor(text, "No") ?: return mismatch(text, menuMissing = true)
            resumeDeclines++
            lastChangeAt = clock()
            return Action.Reply(no)
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
                lastChangeAt = clock()
                pinPrompt = text
                state = RunState.AwaitingPin(Prompt.details(text), clock())
                if (pin != null) usePresetPin(text) else Action.None
            }
        }
    }

    /**
     * Sends the PIN entered up front, but only into a summary that matches: the same recipient and
     * amount as the flow. Anything else cancels (no money moves). A fee holds it for a go-ahead.
     */
    private fun usePresetPin(text: String): Action {
        val p = pin ?: return Action.None
        val details = Prompt.details(text).associate { (k, v) -> k.lowercase() to v }
        flow.recipient?.let { want ->
            if (digits(details["to"]) != digits(want)) return mismatch(text)
        }
        flow.amount?.let { want ->
            if (amountOf(details["amount"]) != amountOf(want)) return mismatch(text)
        }
        // A fee Chalaiden can't read is shown too, rather than taken as free.
        val fee = (details["fee"] ?: details["charge"])?.takeIf { amountOf(it).let { a -> a == null || a > 0.0 } }
        if (fee != null) {
            state = RunState.AwaitingPin(Prompt.details(text), clock(), feeToConfirm = fee)
            return Action.None
        }
        return submitPin(p)
    }

    private fun digits(s: String?) = s?.filter { it.isDigit() }?.takeLast(11)

    /**
     * The one number in an amount ("Tk 1,000.00" -> 1000.0). Anything with more than one ("Tk 10 000")
     * is unreadable rather than guessed, so it can only cancel, never match a different amount.
     */
    private fun amountOf(s: String?): Double? {
        val numbers = Regex("""\d+(?:\.\d+)?""").findAll(s?.replace(",", "") ?: return null).toList()
        return numbers.singleOrNull()?.value?.toDoubleOrNull()
    }

    private fun Dialog.textOrEmpty() = when (this) {
        is Dialog.Prompt -> text
        is Dialog.Notice -> text
        Dialog.Progress -> ""
    }

    private fun moveTo(index: Int) {
        lastChangeAt = clock()
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
        pin = null
        lastChangeAt = clock()
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

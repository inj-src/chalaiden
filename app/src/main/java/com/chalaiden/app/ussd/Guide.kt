package com.chalaiden.app.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One thing to do in bKash's `*247#` menu by hand, in guided mode (no root, no Shizuku). Chalaiden
 * can't see the menu then, so it says what to look for and what to type; the user moves on.
 */
data class GuideStep(
    val kind: Kind,
    /** What to do, e.g. "Choose Payment", "Type the merchant number". */
    val title: String,
    /** What to type: the number, the amount, the reference. Null for a menu pick and the PIN. */
    val value: String? = null,
    /** A quieter line under the title. */
    val hint: String? = null,
    /**
     * What the Chalaiden keyboard types into bKash's box: [value], or a menu pick's usual number. Null
     * for the PIN (never typed by Chalaiden) and for a pick whose number isn't known.
     */
    val fill: String? = value,
) {
    /** [Resume]: bKash's "Do you want to continue? 1 - Yes 2 - No", left over from an unfinished session. */
    enum class Kind { Resume, Pick, Number, Amount, Reference, Pin }
}

object Guide {
    /**
     * Menu positions seen on Grameenphone, 2026-11-02. Only a hint: bKash can reorder its menu, so the
     * guide leads with the label and the user picks whatever number bKash shows next to it.
     */
    val MENU_NUMBERS = mapOf(
        "Payment" to "4", "Send Money" to "1", "Cash Out" to "5", "From Agent" to "1",
        "My bKash" to "9", "Check Balance" to "1",
    )

    /** What says No to bKash's offer to continue an unfinished session. */
    const val RESUME_NO = "2"

    /**
     * [oldSession]: an earlier guided session was left open in bKash, so its first question is probably
     * "Do you want to continue?". It gets a No first; Yes would carry on with that old payment.
     */
    fun steps(flow: UssdFlow, oldSession: Boolean = false): List<GuideStep> =
        listOfNotNull(GuideStep(GuideStep.Kind.Resume, "Continue old session? No", fill = RESUME_NO).takeIf { oldSession }) +
            menuSteps(flow)

    private fun menuSteps(flow: UssdFlow): List<GuideStep> = flow.steps.map { step ->
        when (step) {
            is Step.Pick -> GuideStep(
                GuideStep.Kind.Pick,
                "Choose ${step.label}",
                fill = MENU_NUMBERS[step.label],
                hint = MENU_NUMBERS[step.label]?.let { "Usually $it. Type the number bKash shows next to ${step.label}." }
                    ?: "Type the number bKash shows next to ${step.label}.",
            )
            // By what the step asks for, not by value: a reference can be the same digits as the amount.
            is Step.Answer -> when {
                step.describe.contains("amount", ignoreCase = true) ->
                    GuideStep(GuideStep.Kind.Amount, "Type the amount", step.value, hint = "Digits only, no Tk.")
                step.describe.contains("reference", ignoreCase = true) -> GuideStep(
                    GuideStep.Kind.Reference, "Type the reference", step.value,
                    hint = if (Flows.isDefaultReference(step.value)) "You left it empty; bKash needs one, and any word works." else null,
                )
                else -> GuideStep(GuideStep.Kind.Number, "Type the ${whose(flow.kind)} number", step.value)
            }
            // Kept to the title: bKash's own screen shows the number and amount right under the card.
            Step.Pin -> GuideStep(GuideStep.Kind.Pin, "Enter your PIN")
        }
    }

    private fun whose(kind: FlowKind) = when (kind) {
        FlowKind.Payment -> "merchant"
        FlowKind.SendMoney -> "receiver's"
        FlowKind.CashOut -> "agent"
        FlowKind.Balance -> "account"
    }
}

/**
 * The guide that floats over bKash's menu ([com.chalaiden.app.ui.GuideCover]) and the Chalaiden keyboard
 * share this. Nothing here reads the phone: in guided mode Chalaiden can't see bKash's dialog, so the
 * state follows what the keyboard and the user do, and [Lifecycle] works out what bKash was left with.
 */
object GuideSession {
    /**
     * [filled]: the step whose reply the Chalaiden keyboard last typed, if any. [edited]: the user changed
     * that reply on the keyboard, so it answered something bKash asked unexpectedly (see [KeyboardFill]).
     * [pinAt]: when the PIN step came up, if it has.
     */
    data class State(
        val flow: UssdFlow,
        val steps: List<GuideStep>,
        val index: Int = 0,
        val collapsed: Boolean = false,
        val filled: Int? = null,
        val edited: Boolean = false,
        val pinAt: Long? = null,
    ) {
        val step get() = steps[index]
        val isLast get() = index == steps.lastIndex
    }

    /** How a guide ended. */
    enum class End {
        /** The card's ✕: the user may carry on in bKash by hand, so nothing is assumed about bKash. */
        Closed,

        /** bKash's dialog went away: Cancel, a finished payment, bKash timing out. */
        Gone,

        /**
         * bKash answered the PIN with a new prompt (its receipt or balance, with "0. Main Menu"). That
         * answer is still a live menu: closing it leaves the session open, as a Cancel does.
         */
        Answered,
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _appVisible = MutableStateFlow(false)

    /** True while Chalaiden's own screen is in front; the floating card stays hidden then. */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    /** Where "bKash was left with an open session" is kept between guides (and app restarts). */
    @Volatile private var prefs: Prefs? = null

    /** Called by the activity and by the keyboard, whichever starts first; both run in the app's process. */
    fun bind(context: android.content.Context) {
        if (prefs == null) prefs = Prefs(context.applicationContext)
    }

    /** Starts the guide for [flow] at its first step: every dial opens bKash's menu from the top. */
    fun start(flow: UssdFlow, now: Long = System.currentTimeMillis()) {
        val old = prefs?.let { p -> Lifecycle.stillOpen(p.bkashLeftOpenAt, now).also { p.bkashLeftOpenAt = 0L } } ?: false
        _state.value = State(flow, Guide.steps(flow, oldSession = old))
    }

    fun go(index: Int) = update { it.copy(index = index.coerceIn(0, it.steps.lastIndex), collapsed = false) }
    fun next() = update { it.copy(index = (it.index + 1).coerceAtMost(it.steps.lastIndex)) }
    fun back() = update { it.copy(index = (it.index - 1).coerceAtLeast(0)) }
    fun toggleCollapsed() = update { it.copy(collapsed = !it.collapsed) }

    /** The keyboard typed step [index]'s reply (or the user typed one it didn't know): the card follows. */
    fun filled(index: Int) = update {
        it.copy(index = index.coerceIn(0, it.steps.lastIndex), filled = index, edited = false, collapsed = false)
    }

    /** The user changed the reply the keyboard typed. */
    fun edited() = update { if (it.filled == it.index) it.copy(edited = true) else it }

    /** bKash asked for the PIN: the card shows that step. */
    fun reached(index: Int) = go(index)

    /**
     * The guide is over. [at]: when bKash's dialog was last seen going, for [End.Gone]. Remembers whether
     * bKash still holds the session, so the next guide starts by saying No to it.
     */
    fun end(how: End, at: Long = System.currentTimeMillis()) {
        val last = _state.value ?: return
        if (Lifecycle.leftOpen(last, how, at)) prefs?.bkashLeftOpenAt = at
        _state.value = null
    }

    fun setAppVisible(visible: Boolean) { _appVisible.value = visible }

    private inline fun update(change: (State) -> State) {
        val now = System.currentTimeMillis()
        _state.value = _state.value?.let(change)?.let { s ->
            if (s.pinAt == null && s.step.kind == GuideStep.Kind.Pin) s.copy(pinAt = now) else s
        }
    }

    /**
     * What bKash is left holding when a guide ends, worked out from the steps alone. Measured on
     * Grameenphone, 2026-11-08: cancelling after one or more replies, or at the PIN, leaves the session
     * open, and the next `*247#` first asks "Do you want to continue?". Cancelling at the very first menu
     * leaves nothing. Answering that question (either way) or cancelling it clears it. Seen again the
     * same day: cancelling bKash's answer to the PIN (the balance, with "0. Main Menu") leaves it open too.
     */
    object Lifecycle {
        /** How long bKash is taken to keep an open session. A guess; past it, the No step isn't added. */
        const val OPEN_MILLIS = 10 * 60_000L

        /**
         * A PIN step closed this soon after it came up was cancelled, not paid: typing a PIN, bKash's
         * reply and its OK take longer than this.
         */
        const val QUICK_PIN_CANCEL_MILLIS = 7_000L

        fun leftOpen(state: State, how: End, at: Long): Boolean {
            if (how == End.Answered) return true
            if (how != End.Gone) return false
            state.pinAt?.let { return at - it < QUICK_PIN_CANCEL_MILLIS }
            // Past the first menu means a reply went through. The No to an old session doesn't count:
            // after it bKash is back at its first menu.
            val firstMenu = state.steps.indexOfFirst { it.kind != GuideStep.Kind.Resume }
            return state.index > firstMenu
        }

        fun stillOpen(leftOpenAt: Long, now: Long): Boolean = leftOpenAt > 0 && now - leftOpenAt in 0 until OPEN_MILLIS
    }
}

/** What the Chalaiden keyboard does when a text box opens. */
sealed interface KeyMove {
    /** Leave the box alone (it already has text, e.g. the keyboard was shown again). */
    data object None : KeyMove

    /** Type [text] as step [index]'s reply. */
    data class Type(val text: String, val index: Int) : KeyMove

    /** Switch back to the user's own keyboard. */
    data class HandBack(val index: Int? = null) : KeyMove

    /**
     * bKash asks for the PIN (step [index]): stay up and show the PIN pad. The user types it; Chalaiden
     * only passes each digit into bKash's box and never types, reads back or keeps the PIN itself.
     */
    data class PinPad(val index: Int) : KeyMove

    /** bKash answered the PIN with a new box: the guide is over, and the keyboard steps aside. */
    data object Finished : KeyMove
}

object KeyboardFill {
    /** The phone app that hosts bKash's USSD box. */
    const val PHONE_PACKAGE = "com.android.phone"

    /**
     * [guide]: the running guide, if any. [inPhone]: the box belongs to the phone app's USSD dialog.
     * [empty]: the box has no text yet. Each new bKash prompt opens an empty box, so an empty box right
     * after a reply was typed means bKash has moved on to the next step. Unless the user changed that
     * reply: then it answered a question bKash slipped in ("Do you want to continue?", "Invalid amount,
     * try again"), and the step it was meant for is still to come, so the same reply is typed again.
     * [pinSent]: the PIN box closed with digits in it. The PIN is the last step, so a new box after it
     * is bKash's answer ("Current balance… 0. Main Menu"), not another PIN prompt.
     */
    fun decide(guide: GuideSession.State?, inPhone: Boolean, empty: Boolean, pinSent: Boolean = false): KeyMove {
        if (guide == null || !inPhone) return KeyMove.HandBack()
        if (!empty) return KeyMove.None
        if (pinSent && guide.step.kind == GuideStep.Kind.Pin) return KeyMove.Finished
        val index = if (guide.filled == guide.index && !guide.edited) guide.index + 1 else guide.index
        val step = guide.steps.getOrNull(index) ?: return KeyMove.HandBack()
        // The PIN is never filled in: the user types it on the keyboard's PIN pad.
        if (step.kind == GuideStep.Kind.Pin) return KeyMove.PinPad(index)
        return step.fill?.let { KeyMove.Type(it, index) } ?: KeyMove.None
    }
}

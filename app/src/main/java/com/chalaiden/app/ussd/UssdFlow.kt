package com.chalaiden.app.ussd

/** One exchange in a `*247#` session, in the order bKash asks for it. */
sealed interface Step {
    /** Pick a menu entry by its label, so a reordered menu still works. */
    data class Pick(val label: String) : Step

    /**
     * Answer a prompt whose last line matches [prompt]. [later]: the value isn't known yet (the amount,
     * typed while the menu is walked); the session waits at this prompt until it is given.
     */
    data class Answer(val prompt: Regex, val value: String, val describe: String, val later: Boolean = false) : Step

    /** bKash's confirmation screen ending in "Enter Menu PIN…". The user takes over here. */
    data object Pin : Step
}

enum class FlowKind { Payment, SendMoney, CashOut, Balance }

data class UssdFlow(
    val kind: FlowKind,
    val steps: List<Step>,
    val recipient: String? = null,
    val amount: String? = null,
    val reference: String? = null,
    /** How the shop's card groups [recipient]'s digits (e.g. 5-3-3), for display only; null for the usual 3-4-4. */
    val groups: List<Int>? = null,
    /**
     * Started before the amount was typed: bKash's menu is walked from the number screen, and the amount
     * step is part of the run screen. Stays true once the amount is in, so back from the PIN returns there.
     */
    val amountLater: Boolean = false,
) {
    /** The amount step is still to come. */
    val needsAmount: Boolean get() = amountLater && amount == null

    /** The same flow with the amount typed in, for the step that waited for it. */
    fun withAmount(value: String?): UssdFlow = copy(
        amount = value,
        steps = steps.map { if (it is Step.Answer && it.later) it.copy(value = value.orEmpty()) else it },
    )
}

object Flows {
    const val CODE = "*247#"

    /**
     * Sent when the user leaves the reference empty (bKash requires one), and hidden wherever bKash echoes it.
     * Fixed, so it can be recognised; short, letters and digits only, because bKash rejects symbols
     * ("<no-reference>" came back invalid); and it says where the payment came from.
     */
    const val DEFAULT_REFERENCE = "ChalaiDen"

    /**
     * Characters bKash takes in a reference and `input text` can type: ASCII letters, digits and
     * spaces. Anything else is dropped as the user types.
     */
    fun cleanReference(input: String): String =
        input.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }.trimStart().take(20)

    /** True for the placeholder above, however bKash echoes it back. */
    fun isDefaultReference(value: String?): Boolean = value?.trim().equals(DEFAULT_REFERENCE, ignoreCase = true)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** [amount] null: typed later, while the menu is walked (see [UssdFlow.amountLater]). */
    fun payment(merchant: String, amount: String?, reference: String?): UssdFlow {
        val ref = reference?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE
        return UssdFlow(
            kind = FlowKind.Payment,
            steps = listOf(
                Step.Pick("Payment"),
                Step.Answer(rx("merchant.*account no"), merchant, "Entering shop number"),
                amountStep(amount),
                Step.Answer(rx("enter reference"), ref, "Adding reference"),
                Step.Pin,
            ),
            recipient = merchant, amount = amount, reference = ref, amountLater = amount == null,
        )
    }

    fun sendMoney(receiver: String, amount: String?, reference: String?): UssdFlow {
        val ref = reference?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE
        return UssdFlow(
            kind = FlowKind.SendMoney,
            steps = listOf(
                Step.Pick("Send Money"),
                Step.Answer(rx("receiver.*account no"), receiver, "Entering receiver number"),
                amountStep(amount),
                Step.Answer(rx("enter reference"), ref, "Adding reference"),
                Step.Pin,
            ),
            recipient = receiver, amount = amount, reference = ref, amountLater = amount == null,
        )
    }

    /** Cash out at an agent (Grameenphone, 2026-11-05): Cash Out → From Agent → agent number → amount → PIN. */
    fun cashOut(agent: String, amount: String?): UssdFlow = UssdFlow(
        kind = FlowKind.CashOut,
        steps = listOf(
            Step.Pick("Cash Out"),
            Step.Pick("From Agent"),
            Step.Answer(rx("agent.*account no"), agent, "Entering agent number"),
            amountStep(amount),
            Step.Pin,
        ),
        recipient = agent, amount = amount, amountLater = amount == null,
    )

    private fun amountStep(amount: String?) =
        Step.Answer(rx("enter amount"), amount.orEmpty(), "Entering amount", later = amount == null)

    fun balance(): UssdFlow = UssdFlow(
        kind = FlowKind.Balance,
        steps = listOf(
            Step.Pick("My bKash"),
            Step.Pick("Check Balance"),
            Step.Pin,
        ),
    )
}

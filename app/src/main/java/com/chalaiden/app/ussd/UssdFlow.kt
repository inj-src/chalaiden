package com.chalaiden.app.ussd

/** One exchange in a `*247#` session, in the order bKash asks for it. */
sealed interface Step {
    /** Pick a menu entry by its label, so a reordered menu still works. */
    data class Pick(val label: String) : Step

    /** Answer a prompt whose last line matches [prompt]. */
    data class Answer(val prompt: Regex, val value: String, val describe: String) : Step

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
)

object Flows {
    const val CODE = "*247#"

    /**
     * Sent when the user leaves the reference empty (bKash requires one), and hidden wherever bKash echoes it.
     * Fixed, so it can be recognised; short, letters and digits only, because bKash rejects symbols
     * ("<no-reference>" came back invalid); and not something a person would type as a real reference.
     */
    const val DEFAULT_REFERENCE = "ZQX7K"

    /**
     * Characters bKash takes in a reference and `input text` can type: ASCII letters, digits and
     * spaces. Anything else is dropped as the user types.
     */
    fun cleanReference(input: String): String =
        input.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }.trimStart().take(20)

    /** True for the placeholder above, however bKash echoes it back. */
    fun isDefaultReference(value: String?): Boolean = value?.trim().equals(DEFAULT_REFERENCE, ignoreCase = true)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    fun payment(merchant: String, amount: String, reference: String?): UssdFlow {
        val ref = reference?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE
        return UssdFlow(
            kind = FlowKind.Payment,
            steps = listOf(
                Step.Pick("Payment"),
                Step.Answer(rx("merchant.*account no"), merchant, "Entering merchant number"),
                Step.Answer(rx("enter amount"), amount, "Entering amount"),
                Step.Answer(rx("enter reference"), ref, "Adding reference"),
                Step.Pin,
            ),
            recipient = merchant, amount = amount, reference = ref,
        )
    }

    fun sendMoney(receiver: String, amount: String, reference: String?): UssdFlow {
        val ref = reference?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE
        return UssdFlow(
            kind = FlowKind.SendMoney,
            steps = listOf(
                Step.Pick("Send Money"),
                Step.Answer(rx("receiver.*account no"), receiver, "Entering receiver number"),
                Step.Answer(rx("enter amount"), amount, "Entering amount"),
                Step.Answer(rx("enter reference"), ref, "Adding reference"),
                Step.Pin,
            ),
            recipient = receiver, amount = amount, reference = ref,
        )
    }

    /** Cash out at an agent (Grameenphone, 2026-11-05): Cash Out → From Agent → agent number → amount → PIN. */
    fun cashOut(agent: String, amount: String): UssdFlow = UssdFlow(
        kind = FlowKind.CashOut,
        steps = listOf(
            Step.Pick("Cash Out"),
            Step.Pick("From Agent"),
            Step.Answer(rx("agent.*account no"), agent, "Entering agent number"),
            Step.Answer(rx("enter amount"), amount, "Entering amount"),
            Step.Pin,
        ),
        recipient = agent, amount = amount,
    )

    fun balance(): UssdFlow = UssdFlow(
        kind = FlowKind.Balance,
        steps = listOf(
            Step.Pick("My bKash"),
            Step.Pick("Check Balance"),
            Step.Pin,
        ),
    )
}

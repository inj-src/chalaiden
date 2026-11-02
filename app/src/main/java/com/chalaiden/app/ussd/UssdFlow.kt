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

enum class FlowKind { Payment, SendMoney, Balance }

data class UssdFlow(
    val kind: FlowKind,
    val steps: List<Step>,
    val recipient: String? = null,
    val amount: String? = null,
    val reference: String? = null,
)

object Flows {
    const val CODE = "*247#"

    /** Used when the user leaves the reference empty; bKash requires one. */
    const val DEFAULT_REFERENCE = "1"

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

    fun balance(): UssdFlow = UssdFlow(
        kind = FlowKind.Balance,
        steps = listOf(
            Step.Pick("My bKash"),
            Step.Pick("Check Balance"),
            Step.Pin,
        ),
    )
}

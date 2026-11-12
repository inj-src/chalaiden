package com.chalaiden.app.ussd

/** Text helpers for USSD dialogs. bKash prepends changing promo lines, so the last line is the real prompt. */
object Prompt {
    // "4 Payment", "0. Main Menu", "2 - No", and "9.My bKash" with no space (Banglalink, vivo V2445A, 2026-11-12)
    private val menuLine = Regex("""^(\d{1,2})(?:\s*[.):-]\s*|\s+)(\S.*)$""")
    private val keyValue = Regex("""^([A-Za-z][A-Za-z ]{1,24}):\s*(.+)$""")
    private val trxId = Regex("""TrxID[:\s]*([A-Z0-9]{6,})""", RegexOption.IGNORE_CASE)

    /**
     * The phone app's wait between exchanges, not a message from bKash. Stock Android and OxygenOS say
     * "Running USSD code…"; vivo says "USSD code running…" (V2445A, Android 15, OriginOS 5, 2026-11-12).
     */
    fun isProgress(text: String): Boolean =
        text.contains("Running USSD", ignoreCase = true) || text.contains("USSD code running", ignoreCase = true)

    /**
     * Where the phone app puts bKash's text, in order of trust. vivo's dialog puts it in the title
     * (`alertTitle`), so that comes after the stock ids: elsewhere a title is not the message.
     */
    val MESSAGE_IDS = listOf("com.android.phone:id/message_field", "android:id/message", "com.android.phone:id/alertTitle")

    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun lastLine(text: String): String = lines(text).lastOrNull().orEmpty()

    /** Menu label → the number to reply with. Labels compare case-insensitively and exactly. */
    fun menuOptions(text: String): Map<String, String> =
        lines(text).mapNotNull { menuLine.matchEntire(it) }
            .associate { it.groupValues[2].trim().lowercase() to it.groupValues[1] }

    fun optionFor(text: String, label: String): String? = menuOptions(text)[label.lowercase()]

    /** "Do you want to continue? 1 - Yes 2 - No", shown when the last session was left unfinished. */
    fun isResumeOffer(text: String): Boolean =
        text.contains("want to continue", ignoreCase = true) && optionFor(text, "No") != null

    fun isPinPrompt(text: String): Boolean = lastLine(text).contains("PIN", ignoreCase = false)

    /** "To: 017…", "Amount: Tk 10", … from the confirmation screen, in display order. */
    fun details(text: String): List<Pair<String, String>> =
        lines(text).dropLast(1).mapNotNull { keyValue.matchEntire(it) }
            .map { it.groupValues[1].trim() to it.groupValues[2].trim() }

    private val balance = Regex("""(Current|Available) balance\s*Tk\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    /** "Current" → "3,299.05", "Available" → "3,299.05" from a balance reply. */
    fun balances(text: String): Map<String, String> =
        balance.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    fun trxId(text: String): String? = trxId.find(text)?.groupValues?.get(1)
}

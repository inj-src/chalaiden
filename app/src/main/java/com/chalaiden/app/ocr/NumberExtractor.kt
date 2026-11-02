package com.chalaiden.app.ocr

/**
 * Finds Bangladeshi mobile numbers in OCR text. Merchant cards print them spaced or in
 * separate boxes ("0 1 7 | 4 5 6 0 | 6 3 0 6"), and OCR often mistakes digits for letters.
 */
object NumberExtractor {
    private val mobile = Regex("""(?:880)?(01[3-9]\d{8})""")
    private val lookalikes = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    /** All distinct numbers found in [line], in reading order. */
    fun find(line: String): List<String> {
        // Only fix lookalikes in lines that are mostly digits, so words stay words.
        val compact = line.filterNot { it.isWhitespace() || it == '-' || it == '.' || it == '+' }
        val digitShare = compact.count { it.isDigit() }.toFloat() / compact.length.coerceAtLeast(1)
        val normalized = if (digitShare >= 0.7f) compact.map { lookalikes[it] ?: it }.joinToString("") else compact
        val digits = normalized.filter { it.isDigit() || it.isLetter() }.split(Regex("[^0-9]+")).joinToString(" ")
        return digits.split(' ').flatMap { run -> mobile.findAll(run).map { it.groupValues[1] }.toList() }.distinct()
    }

    fun isValid(number: String) = Regex("""01[3-9]\d{8}""").matches(number)
}

package com.chalaiden.app.ocr

/**
 * Finds Bangladeshi mobile numbers in OCR text. Merchant cards print them spaced or in
 * separate boxes ("0 1 7 | 4 5 6 0 | 6 3 0 6"), and OCR often mistakes digits for letters.
 */
object NumberExtractor {
    private val mobile = Regex("""(?:880)?(01[3-9]\d{8})""")
    private val lookalikes = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'I' to '1', 'l' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    /**
     * All distinct numbers found in [line], in reading order.
     *
     * A '|' is usually the edge between two boxes, so it is first read as nothing. Reading it as a
     * 1 would add digits ("01|7 4560|6306" becoming 0171456016306) and the search could then pick
     * a wrong number out of the middle. Only when that finds nothing is '|' tried as a thin 1.
     */
    fun find(line: String): List<String> =
        search(line.replace(PIPE, ' ')).ifEmpty { if (PIPE in line) search(line.replace(PIPE, '1')) else emptyList() }

    private fun search(line: String): List<String> {
        val compact = compact(line)
        val normalized = if (isLenient(line)) compact.map { lookalikes[it] ?: it }.joinToString("") else compact
        val digits = normalized.filter { it.isDigit() || it.isLetter() }.split(Regex("[^0-9]+")).joinToString(" ")
        return digits.split(' ').flatMap { run -> mobile.findAll(run).map { it.groupValues[1] }.toList() }.distinct()
    }

    /** Only fix lookalikes in text that is mostly digits, so words stay words. */
    fun isLenient(text: String): Boolean {
        val compact = compact(text)
        return compact.count { it.isDigit() }.toFloat() / compact.length.coerceAtLeast(1) >= 0.7f
    }

    /**
     * [c] as a digit, reading lookalike letters as digits when [lenient]; null for anything else.
     * A '|' counts as a 1 only when [pipeIsOne] (see [find]); otherwise it is a box edge.
     */
    fun digitOf(c: Char, lenient: Boolean, pipeIsOne: Boolean = false): Char? = when {
        c.isDigit() -> c
        c == PIPE -> if (pipeIsOne && lenient) '1' else null
        lenient -> lookalikes[c]
        else -> null
    }

    private const val PIPE = '|'

    private fun compact(text: String) = text.filterNot { it.isWhitespace() || it == '-' || it == '.' || it == '+' }

    fun isValid(number: String) = Regex("""01[3-9]\d{8}""").matches(number)
}

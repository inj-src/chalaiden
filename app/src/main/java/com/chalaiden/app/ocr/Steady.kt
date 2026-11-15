package com.chalaiden.app.ocr

/**
 * Decides when the number in view can be taken without a press: an 11-digit number has stayed in
 * view for [holdMillis]. Live OCR rarely reads the same 11 digits twice in a row (one frame takes a 6
 * for an 8, the next a 1 for a 7), so the reads are not required to match. Instead each position is
 * decided by vote over the last [holdMillis] of reads ([consensus]), and the vote is only trusted
 * when most reads are within a digit or two of it, so two different numbers alternating in view
 * (panning across a card) are never spliced into one.
 *
 * A frame with no number at all is forgiven for [graceMillis], as OCR drops a number now and then
 * even when it is in view; longer than that and the wait starts over.
 */
class Steady(private val holdMillis: Long = 2_000L, private val graceMillis: Long = NumberAnalyzer.FRESH_MILLIS) {
    private class Read(val number: String, val at: Long)

    private val reads = ArrayDeque<Read>()
    private var since = 0L

    /** One frame's read at [now], [seen] null when it had no number. The number to take once one has held, else null. */
    fun feed(seen: String?, now: Long): String? {
        if (reads.isNotEmpty() && now - reads.last().at > graceMillis) reads.clear()
        if (seen == null) return null
        if (reads.isEmpty()) since = now
        reads.addLast(Read(seen, now))
        // Only the last [holdMillis] vote, so an early misread (or the number before a pan) ages out.
        while (now - reads.first().at > holdMillis) reads.removeFirst()
        if (now - since < holdMillis) return null
        val window = reads.map { it.number }
        val agreed = consensus(window)
        val close = window.count { differences(it, agreed) <= CLOSE_DIGITS }
        // The oldest read must be this number too, so after a pan the new number holds a full [holdMillis].
        val heldThroughout = differences(window.first(), agreed) <= CLOSE_DIGITS
        return agreed.takeIf { heldThroughout && close >= window.size * AGREEMENT }
    }

    companion object {
        /** A read this many digits off the vote still counts as the same number, misread. */
        const val CLOSE_DIGITS = 2

        /** The share of reads that must be close to the vote for it to be taken. */
        const val AGREEMENT = 0.6

        /** Each position's most common digit; a tie goes to the latest read. [reads] are all 11 digits. */
        fun consensus(reads: List<String>): String = (0 until reads.last().length).map { i ->
            val counts = reads.groupingBy { it[i] }.eachCount()
            val top = counts.values.max()
            reads.last { counts[it[i]] == top }[i]
        }.joinToString("")

        private fun differences(a: String, b: String) = a.indices.count { a[it] != b.getOrNull(it) } + maxOf(0, b.length - a.length)
    }
}

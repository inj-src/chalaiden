package com.chalaiden.app.ocr

/**
 * Decides when the number in view can be taken without a press: the same number, read frame after
 * frame, for [holdMillis]. A frame that reads a different number starts the wait over, so a misread
 * (a box edge taken for a 1, a smudged digit) is never taken just because it showed up once.
 * A frame with no number at all is forgiven for [graceMillis], as OCR drops a number now and then
 * even when it is in view.
 */
class Steady(private val holdMillis: Long = 2_000L, private val graceMillis: Long = NumberAnalyzer.FRESH_MILLIS) {
    private var number: String? = null
    private var since = 0L
    private var lastSeen = 0L

    /** One frame's read at [now], [seen] null when it had no number. True once the number has held. */
    fun feed(seen: String?, now: Long): Boolean {
        val lost = number != null && now - lastSeen > graceMillis
        if (seen == null) {
            if (lost) number = null
            return false
        }
        if (seen != number || lost) {
            number = seen
            since = now
        }
        lastSeen = now
        return now - since >= holdMillis
    }
}

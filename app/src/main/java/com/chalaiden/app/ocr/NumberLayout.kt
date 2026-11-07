package com.chalaiden.app.ocr

import kotlin.math.hypot

/** A point in image pixels. */
data class Pt(val x: Float, val y: Float)

/** One character OCR read, with its corners in reading orientation: top-left, top-right, bottom-right, bottom-left. */
data class Glyph(val char: Char, val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt)

/**
 * Where a read number sits on the card. [groups]: how the card itself groups the digits
 * (01331 065 077 is 5-3-3, 017 4560 6306 is 3-4-4), or null when that couldn't be told.
 */
data class NumberSpan(val groups: List<Int>?, val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt)

/**
 * Finds a number's digits among the characters OCR read on a row, and how the card spaces them.
 * Pure geometry, so it is tested without a camera.
 */
object NumberLayout {
    /** bKash's usual grouping, for typed numbers and cards whose spacing can't be read. */
    val fallback = listOf(3, 4, 4)

    /** A gap this much wider than the usual step between digits is a space on the card. */
    private const val SPACE_RATIO = 1.3f

    /** Spaces must stand out this clearly from the widest ordinary step, or the reading is a guess. */
    private const val CLEAR_RATIO = 1.15f

    private class Digit(val char: Char, val glyph: Glyph, val breakBefore: Boolean)

    /** [number]'s place in [glyphs] (one row, in reading order), or null if its digits aren't there in a run. */
    fun locate(number: String, glyphs: List<Glyph>): NumberSpan? {
        val lenient = NumberExtractor.isLenient(glyphs.map { it.char }.joinToString(""))
        // Runs of digits, as NumberExtractor reads them: letters end a run; other marks ('-', '.',
        // a box edge) are skipped over but remembered as a break the card printed.
        val runs = mutableListOf(mutableListOf<Digit>())
        var marked = false
        for (g in glyphs) {
            val d = NumberExtractor.digitOf(g.char, lenient)
            when {
                d != null -> { runs.last() += Digit(d, g, marked); marked = false }
                g.char.isLetter() -> { runs += mutableListOf<Digit>(); marked = false }
                g.char != '+' && !g.char.isWhitespace() -> marked = true
            }
        }
        for (run in runs) {
            val at = run.map { it.char }.joinToString("").indexOf(number)
            if (at < 0) continue
            val digits = run.subList(at, at + number.length)
            val first = digits.first().glyph
            val last = digits.last().glyph
            return NumberSpan(groups(digits), first.tl, last.tr, last.br, first.bl)
        }
        return null
    }

    private fun groups(digits: List<Digit>): List<Int>? {
        if (digits.size < 2) return null
        // Measure along the row, so a tilted card reads the same as a straight one.
        val a = center(digits.first().glyph)
        val b = center(digits.last().glyph)
        val len = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: return null
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        // Centre to centre: digits share one advance in nearly every font, and a thin "1" doesn't skew it.
        val along = digits.map { center(it.glyph).let { c -> c.x * ux + c.y * uy } }
        val steps = along.zipWithNext { p, q -> q - p }
        if (steps.any { it <= 0f }) return null // out of order: not one clean row
        val usual = steps.sorted()[steps.size / 2]

        val spaced = steps.indices.filter { steps[it] > usual * SPACE_RATIO }.toSet()
        val clear = spaced.isNotEmpty() &&
            spaced.minOf { steps[it] } >= CLEAR_RATIO * (steps.indices - spaced).maxOf { steps[it] }
        val printed = (1 until digits.size).filter { digits[it].breakBefore }.map { it - 1 }.toSet()
        val breaks = (if (clear) spaced else emptySet()) + printed
        if (breaks.isEmpty()) return null

        val sizes = mutableListOf<Int>()
        var size = 1
        for (i in steps.indices) {
            if (i in breaks) { sizes += size; size = 1 } else size++
        }
        sizes += size
        // Only shapes a phone number is printed in (3-4-4, 5-3-3, 5-6...); a lone digit or a
        // long unbroken stretch means a gap or a mark was misread, so the default is safer.
        return sizes.takeIf { it.size in 2..4 && it.all { s -> s in 2..6 } }
    }

    private fun center(g: Glyph) = Pt((g.tl.x + g.tr.x + g.br.x + g.bl.x) / 4f, (g.tl.y + g.tr.y + g.br.y + g.bl.y) / 4f)
}

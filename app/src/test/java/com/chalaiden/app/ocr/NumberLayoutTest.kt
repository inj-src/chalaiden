package com.chalaiden.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class NumberLayoutTest {
    /**
     * Lays [text] out as OCR would see it: each character [advance] px on, a space adding [space] px,
     * then turned by [degrees]. [jitter] nudges each glyph, as real OCR boxes wobble.
     */
    private fun row(text: String, advance: Float = 24f, space: Float = 22f, degrees: Double = 0.0, jitter: (Int) -> Float = { 0f }): List<Glyph> {
        val out = mutableListOf<Glyph>()
        var x = 100f
        val c = cos(Math.toRadians(degrees)).toFloat()
        val s = sin(Math.toRadians(degrees)).toFloat()
        fun p(px: Float, py: Float) = Pt(px * c - py * s, px * s + py * c)
        text.forEachIndexed { i, ch ->
            if (ch == ' ') { x += space; return@forEachIndexed }
            val l = x + jitter(i)
            val r = l + advance * 0.8f
            out += Glyph(ch, p(l, 0f), p(r, 0f), p(r, 40f), p(l, 40f))
            x += advance
        }
        return out
    }

    private fun groups(number: String, text: String, advance: Float = 24f, space: Float = 22f, degrees: Double = 0.0, jitter: (Int) -> Float = { 0f }) =
        NumberLayout.locate(number, row(text, advance, space, degrees, jitter))?.groups

    @Test fun fiveThreeThree() = assertEquals(listOf(5, 3, 3), groups("01331065077", "01331 065 077"))
    @Test fun threeFourFour() = assertEquals(listOf(3, 4, 4), groups("01745606306", "017 4560 6306"))
    @Test fun fiveThreeThreeFromThePhoto() = assertEquals(listOf(5, 3, 3), groups("01896156558", "01896 156 558"))
    @Test fun fourThreeFour() = assertEquals(listOf(4, 3, 4), groups("01711234567", "0171 123 4567"))

    @Test fun tiltedCardReadsTheSame() =
        assertEquals(listOf(5, 3, 3), groups("01331065077", "01331 065 077", degrees = 17.0))

    @Test fun wobblyBoxesStillGroup() {
        val wobble = listOf(0f, 2f, -2f, 1f, -1f, 0f, 2f, -2f, 1f, 0f, -1f, 2f, 0f)
        assertEquals(listOf(5, 3, 3), groups("01331065077", "01331 065 077") { wobble[it % wobble.size] })
    }

    @Test fun printedDashIsABreak() = assertEquals(listOf(5, 6), groups("01711000000", "01711-000000"))

    @Test fun numberAmongOtherTextIsFound() {
        val span = NumberLayout.locate("01745606306", row("A/C 017 4560 6306"))
        assertEquals(listOf(3, 4, 4), span?.groups)
    }

    @Test fun lookalikeLettersCount() = assertEquals(listOf(3, 4, 4), groups("01745606306", "O17 4560 63O6"))

    /** One digit per evenly spaced box: there is no grouping to copy, so the safe default is used. */
    @Test fun evenlySpacedDigitsHaveNoGrouping() = assertNull(groups("01745606306", "0 1 7 4 5 6 0 6 3 0 6", space = 6f))

    /** A gap only a little wider than the rest is noise, not a space. */
    @Test fun unclearGapIsNotGuessed() = assertNull(groups("01745606306", "017 45606306", space = 4f))

    /** A single digit cut off on its own isn't a shape phone numbers are printed in. */
    @Test fun loneDigitGroupIsRejected() = assertNull(groups("01745606306", "0 1745606306"))

    /** A divider misread as a digit can leave one long stretch; that isn't a printed shape either. */
    @Test fun longUnbrokenGroupIsRejected() = assertNull(groups("01718300700", "01718300 700"))

    @Test fun cornersSpanJustTheNumber() {
        val span = NumberLayout.locate("01745606306", row("A/C 017 4560 6306"))!!
        val glyphs = row("A/C 017 4560 6306")
        assertEquals(glyphs[3].tl, span.tl) // the "0" after "A/C"
        assertEquals(glyphs.last().br, span.br)
    }

    @Test fun missingNumberIsNull() = assertNull(NumberLayout.locate("01745606306", row("017 4560 630")))
}

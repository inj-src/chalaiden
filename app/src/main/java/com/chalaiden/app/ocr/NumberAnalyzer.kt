package com.chalaiden.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A number read from an image, with the strip it was read from for the user to compare, and how
 * the card groups its digits ([NumberLayout.fallback] when that couldn't be read).
 */
data class NumberRead(val number: String?, val crop: Bitmap?, val groups: List<Int> = NumberLayout.fallback)

object NumberFinder {
    val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Best number in [text] and where it is. Boxed digits come back as separate lines, so rows are joined first. */
    fun best(text: Text): Pair<String, NumberSpan>? {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val rows = mutableListOf<MutableList<Text.Line>>()
        lines.sortedBy { it.boundingBox!!.centerY() }.forEach { line ->
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf { r ->
                val ref = r.first().boundingBox!!
                abs(ref.centerY() - box.centerY()) < ref.height() / 2
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.mapNotNull { row ->
            val sorted = row.sortedBy { it.boundingBox!!.left }
            val number = NumberExtractor.find(sorted.joinToString(" ") { it.text }).firstOrNull()
                ?: return@mapNotNull null
            val span = NumberLayout.locate(number, sorted.flatMap { line -> line.elements.flatMap(::glyphs) })
                ?: NumberSpan(null, corners(sorted.first())[0], corners(sorted.last())[1], corners(sorted.last())[2], corners(sorted.first())[3])
            number to span
        }.maxByOrNull { (_, s) -> dist(s.tl, s.bl) } // the printed number is usually the biggest one
    }

    /** An element's characters with their corners: OCR's own per-character boxes, else the word split evenly. */
    private fun glyphs(element: Text.Element): List<Glyph> {
        val c = corners(element)
        val symbols = element.symbols
        if (symbols.isNotEmpty() && symbols.all { it.cornerPoints?.size == 4 && it.text.isNotEmpty() && within(corners(it), c) }) {
            return symbols.flatMap { sym -> corners(sym).let { s -> sym.text.map { ch -> Glyph(ch, s[0], s[1], s[2], s[3]) } } }
        }
        val n = element.text.length.coerceAtLeast(1)
        return element.text.mapIndexed { i, ch ->
            val a = i.toFloat() / n
            val b = (i + 1).toFloat() / n
            Glyph(ch, lerp(c[0], c[1], a), lerp(c[0], c[1], b), lerp(c[3], c[2], b), lerp(c[3], c[2], a))
        }
    }

    /** Clockwise from top-left, in the text's own orientation (ML Kit's order); the box if there are none. */
    private fun corners(points: Array<android.graphics.Point>?, box: android.graphics.Rect?): List<Pt> =
        points?.takeIf { it.size == 4 }?.map { Pt(it.x.toFloat(), it.y.toFloat()) }
            ?: box!!.let { b ->
                listOf(Pt(b.left.toFloat(), b.top.toFloat()), Pt(b.right.toFloat(), b.top.toFloat()), Pt(b.right.toFloat(), b.bottom.toFloat()), Pt(b.left.toFloat(), b.bottom.toFloat()))
            }

    /**
     * Whether a character's corners sit on its word. They should; when OCR reports them in some
     * other frame (seen with rotated camera input) the word is split evenly instead.
     */
    private fun within(sym: List<Pt>, word: List<Pt>): Boolean {
        val h = (dist(word[0], word[3]) + dist(word[1], word[2])) / 2f
        val minX = word.minOf { it.x } - h; val maxX = word.maxOf { it.x } + h
        val minY = word.minOf { it.y } - h; val maxY = word.maxOf { it.y } + h
        return sym.all { it.x in minX..maxX && it.y in minY..maxY }
    }

    private fun corners(l: Text.Line) = corners(l.cornerPoints, l.boundingBox)
    private fun corners(e: Text.Element) = corners(e.cornerPoints, e.boundingBox)
    private fun corners(s: Text.Symbol) = corners(s.cornerPoints, s.boundingBox)

    /**
     * The number's strip, straightened: its four corners are mapped onto a flat rectangle, which
     * undoes tilt and most of the slant from holding the phone at an angle. Colours are left as
     * they are: the strip is for checking digits against the card, so it must look like the card.
     */
    fun crop(frame: Bitmap, span: NumberSpan): Bitmap {
        val h = (dist(span.tl, span.bl) + dist(span.tr, span.br)) / 2f
        val w = (dist(span.tl, span.tr) + dist(span.bl, span.br)) / 2f
        if (h < 2f || w < 2f) return frame
        // Unit vectors along and across the row, averaged over both edges.
        val ux = unit(Pt(span.tr.x - span.tl.x + span.br.x - span.bl.x, span.tr.y - span.tl.y + span.br.y - span.bl.y))
        val uy = unit(Pt(span.bl.x - span.tl.x + span.br.x - span.tr.x, span.bl.y - span.tl.y + span.br.y - span.tr.y))
        val padAlong = h * 1.0f
        val padAcross = h * 0.5f
        fun out(p: Pt, along: Float, across: Float) = Pt(p.x + ux.x * along + uy.x * across, p.y + ux.y * along + uy.y * across)
        val src = listOf(
            out(span.tl, -padAlong, -padAcross), out(span.tr, padAlong, -padAcross),
            out(span.br, padAlong, padAcross), out(span.bl, -padAlong, padAcross),
        )
        // Digits about 96 px tall: sharp on screen without blowing up a tiny, blurry read.
        val scale = (96f / h).coerceIn(0.5f, 3f)
        val outW = ((w + 2 * padAlong) * scale).toInt().coerceIn(1, 2400)
        val outH = ((h + 2 * padAcross) * scale).toInt().coerceIn(1, 600)
        val matrix = Matrix().apply {
            setPolyToPoly(
                src.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
                floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()), 0, 4,
            )
        }
        val strip = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(strip).drawBitmap(frame, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return strip
    }

    /** For gallery photos: same search on a still image. */
    fun read(bitmap: Bitmap): NumberRead {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val (number, span) = best(text) ?: return NumberRead(null, null)
        return NumberRead(number, crop(bitmap, span), span.groups ?: NumberLayout.fallback)
    }

    private fun dist(a: Pt, b: Pt) = hypot(b.x - a.x, b.y - a.y)
    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    private fun unit(v: Pt) = hypot(v.x, v.y).let { l -> if (l == 0f) Pt(1f, 0f) else Pt(v.x / l, v.y / l) }
}

/** What the camera sees right now: the number, how the card groups it, and where it is in the frame. */
data class LiveNumber(val number: String, val groups: List<Int>, val span: NumberSpan, val frameWidth: Int, val frameHeight: Int)

/**
 * Reads camera frames and reports what it sees through [onLive], so the screen can box the number.
 * Nothing is taken until [capture]: then the number in view (or the frame, if there is none) goes to
 * [onCaptured], once.
 *
 * Frames must arrive upright and as RGBA (see the scanner's ImageAnalysis setup). Each is read as a
 * still bitmap, the same path as gallery photos, so the corners OCR reports, the crop and the box
 * drawn on screen all share one coordinate system.
 */
class NumberAnalyzer(
    private val onLive: (LiveNumber?) -> Unit,
    private val onCaptured: (NumberRead) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile private var wanted = false
    @Volatile private var done = false

    private class Sighting(val frame: Bitmap, val number: String, val span: NumberSpan, val at: Long)

    /** The last frame a number was read in. A press uses it if it is recent, so it isn't lost to one blurry frame. */
    private var last: Sighting? = null

    fun capture() { wanted = true }

    override fun analyze(image: ImageProxy) {
        if (done) return image.close()
        val frame = try { image.toBitmap().rotated(image.imageInfo.rotationDegrees) } finally { image.close() }
        val now = System.currentTimeMillis()
        last?.takeIf { wanted && now - it.at < FRESH_MILLIS }?.let { return finish(read(it)) }

        val found = runCatching { Tasks.await(NumberFinder.recognizer.process(InputImage.fromBitmap(frame, 0))) }
            .getOrNull()?.let(NumberFinder::best)
        val seen = found?.let { (number, span) -> Sighting(frame, number, span, now) }
        if (seen != null) last = seen
        onLive(seen?.let { LiveNumber(it.number, it.span.groups ?: NumberLayout.fallback, it.span, frame.width, frame.height) })
        // Pressed with no number in view: hand over the photo so the user can type what they see.
        if (wanted) finish(seen?.let(::read) ?: NumberRead(null, frame))
    }

    private fun read(s: Sighting) = NumberRead(s.number, NumberFinder.crop(s.frame, s.span), s.span.groups ?: NumberLayout.fallback)

    private fun finish(read: NumberRead) {
        done = true
        last = null
        onCaptured(read)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    companion object {
        /** A press counts a number seen this recently as still in view. */
        const val FRESH_MILLIS = 700L
    }
}

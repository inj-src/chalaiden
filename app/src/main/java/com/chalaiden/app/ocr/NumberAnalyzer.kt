package com.chalaiden.app.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** A number read from an image, with the region it was read from cut out for the user to compare. */
data class NumberRead(val number: String?, val crop: Bitmap?)

object NumberFinder {
    val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Best number in [text] and where it is. Boxed digits come back as separate lines, so rows are joined first. */
    fun best(text: Text): Pair<String, Rect>? {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val rows = mutableListOf<MutableList<Text.Line>>()
        lines.sortedBy { it.boundingBox!!.centerY() }.forEach { line ->
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf { r ->
                val ref = r.first().boundingBox!!
                kotlin.math.abs(ref.centerY() - box.centerY()) < ref.height() / 2
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.mapNotNull { row ->
            val sorted = row.sortedBy { it.boundingBox!!.left }
            val number = NumberExtractor.find(sorted.joinToString(" ") { it.text }).firstOrNull()
                ?: return@mapNotNull null
            val box = Rect(sorted.first().boundingBox).apply { sorted.forEach { union(it.boundingBox!!) } }
            number to box
        }.maxByOrNull { it.second.height() } // the printed number is usually the biggest one
    }

    fun crop(frame: Bitmap, box: Rect): Bitmap {
        val padX = (box.width() * 0.08f).toInt() + 8
        val padY = (box.height() * 0.45f).toInt() + 8
        val r = Rect(box.left - padX, box.top - padY, box.right + padX, box.bottom + padY)
        r.intersect(0, 0, frame.width, frame.height)
        return Bitmap.createBitmap(frame, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
    }

    /** For gallery photos: same search on a still image. */
    fun read(bitmap: Bitmap): NumberRead {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val found = best(text) ?: return NumberRead(null, null)
        return NumberRead(found.first, crop(bitmap, found.second))
    }
}

/**
 * Reads camera frames until the same number shows up in [STABLE_FRAMES] frames in a row,
 * then reports it once with a crop. [onLive] gets every candidate so the UI can show progress.
 */
class NumberAnalyzer(
    private val onLive: (String?) -> Unit,
    private val onCaptured: (NumberRead) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var captureNow = false
    @Volatile private var done = false
    private val recent = ArrayDeque<String?>()

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (done || media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        NumberFinder.recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnCompleteListener { task ->
                try {
                    val found = task.result?.let(NumberFinder::best)
                    recent.addLast(found?.first)
                    while (recent.size > STABLE_FRAMES) recent.removeFirst()
                    onLive(found?.first)
                    val stable = found != null && recent.size == STABLE_FRAMES && recent.all { it == found.first }
                    if (stable || captureNow) {
                        done = true
                        val frame = image.toBitmap().rotated(rotation)
                        onCaptured(
                            if (found != null) NumberRead(found.first, NumberFinder.crop(frame, found.second))
                            else NumberRead(null, frame),
                        )
                    }
                } finally {
                    image.close()
                }
            }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    companion object {
        const val STABLE_FRAMES = 3
    }
}

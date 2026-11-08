package com.chalaiden.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Chalaiden's own icons: one round-capped stroke weight, tinted by `Icon`. */
object AppIcons {
    val Check = stroke("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }

    val Cross = stroke("cross") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) }

    /** For "we couldn't tell": an exclamation mark. */
    val Alert = stroke("alert") {
        moveTo(12f, 5.5f); lineTo(12f, 13.5f)
        moveTo(12f, 18.2f); lineTo(12f, 18.3f)
    }

    val Back = stroke("back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }

    val ChevronRight = stroke("chevron-right") { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }

    val ChevronDown = stroke("chevron-down") { moveTo(5.5f, 9.5f); lineTo(12f, 16f); lineTo(18.5f, 9.5f) }

    val ChevronUp = stroke("chevron-up") { moveTo(5.5f, 14.5f); lineTo(12f, 8f); lineTo(18.5f, 14.5f) }

    /** Two overlapping sheets: copy to the clipboard. */
    val Copy = svg("copy", "M9 9h9.5a1.5 1.5 0 0 1 1.5 1.5V20a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 20v-9.5A1.5 1.5 0 0 1 9 9z", "M4 15V5.5A1.5 1.5 0 0 1 5.5 4H15")

    val Backspace = stroke("backspace") {
        moveTo(8.5f, 5.5f); lineTo(20f, 5.5f); lineTo(20f, 18.5f); lineTo(8.5f, 18.5f); lineTo(3f, 12f); close()
        moveTo(11f, 9.5f); lineTo(16f, 14.5f); moveTo(16f, 9.5f); lineTo(11f, 14.5f)
    }

    /** Viewfinder corners with the number line inside. */
    val Scan = stroke("scan") {
        moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f)
        moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f)
        moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f)
        moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f)
        moveTo(8f, 12f); lineTo(16f, 12f)
    }

    /** 3×3 keys: type the number instead of scanning. */
    val Dialpad = stroke("dialpad") {
        for (y in listOf(6f, 12f, 18f)) for (x in listOf(6.5f, 12f, 17.5f)) { moveTo(x, y); lineTo(x + 0.01f, y) }
    }

    val Send = stroke("send") { moveTo(7f, 17f); lineTo(17f, 7f); moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f) }

    /** The taka sign (৳), traced from Noto Sans Bengali: check balance. */
    val Taka = svg(
        "taka",
        "M6.2 4.6C7.6 3.9 10.2 4.2 10.2 6.6V16.5C10.2 19 11.6 20 13 20C15.6 20 17.5 18 17.5 15.6C17.5 13.6 16 12.8 14 13.8",
        "M6.8 9.7H17",
    )

    /** A banknote dropping into an open hand: cash handed over by an agent. */
    val CashOut = svg(
        "cash-out",
        "M7 3.5h11a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
        "M11.1 7.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0-2.8 0",
        "M2.5 14.5h3l4.25 1.5H13a1.6 1.6 0 0 1 0 3.2H9.5",
        "M13.3 18.4l4.7-2.6a1.6 1.6 0 0 1 2 2.4l-5.8 3.3H2.5",
    )

    /** A SIM card: rounded, clipped corner, chip. */
    val Sim = svg(
        "sim",
        "M7.5 3h7l4.5 4.5V19a2 2 0 0 1-2 2H7.5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M10.15 11h3.7a1.4 1.4 0 0 1 1.4 1.4v3.7a1.4 1.4 0 0 1-1.4 1.4h-3.7a1.4 1.4 0 0 1-1.4-1.4v-3.7a1.4 1.4 0 0 1 1.4-1.4z",
    )

    val Torch = stroke("torch") { moveTo(13f, 3f); lineTo(6.5f, 13.5f); lineTo(12f, 13.5f); lineTo(11f, 21f); lineTo(17.5f, 10.5f); lineTo(12f, 10.5f); close() }

    val Photo = stroke("photo") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 16f); moveTo(13f, 14f); lineTo(15.5f, 11.5f); lineTo(20f, 16f)
    }

    val Person = stroke("person") {
        moveTo(12f, 4f); arcTo(4f, 4f, 0f, true, true, 11.99f, 4f)
        moveTo(4.5f, 20f); curveTo(5.5f, 15.5f, 18.5f, 15.5f, 19.5f, 20f)
    }

    val Paste = stroke("paste") {
        moveTo(8f, 5f); lineTo(5.5f, 5f); lineTo(5.5f, 20.5f); lineTo(18.5f, 20.5f); lineTo(18.5f, 5f); lineTo(16f, 5f)
        moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(15f, 6.5f); lineTo(9f, 6.5f); close()
    }

    /** A padlock: where the PIN goes. */
    val Lock = svg(
        "lock",
        "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11z",
        "M8.5 11V7.5a3.5 3.5 0 0 1 7 0V11",
    )

    /** A circle struck through: "stopped before it was sent". */
    val Block = svg("block", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M6 6l12 12")

    /** A clock face: "bKash was still busy, wait a moment". */
    val Clock = svg("clock", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 7.5V12l3 2")

    /** Three dots, for a menu. Filled: thin strokes read as rings at this size. */
    val More = ImageVector.Builder("more", 24.dp, 24.dp, 24f, 24f).apply {
        listOf(5f, 12f, 19f).forEach { y ->
            addPath(
                pathData = addPathNodes("M12 ${y - 1.9f}a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0-3.8z"),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()

    /** Same stroke as [stroke], from SVG path data (easier for curves and rounded corners). */
    private fun svg(name: String, vararg d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            d.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()
}

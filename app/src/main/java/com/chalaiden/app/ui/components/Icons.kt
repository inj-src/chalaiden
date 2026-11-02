package com.chalaiden.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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

    /** Wallet: check balance. */
    val Wallet = stroke("wallet") {
        moveTo(4f, 7.5f); lineTo(4f, 18f); lineTo(20f, 18f); lineTo(20f, 7.5f); close()
        moveTo(4f, 7.5f); lineTo(16.5f, 4.5f); lineTo(17f, 7.5f)
        moveTo(15.5f, 12.75f); lineTo(16.5f, 12.75f)
    }

    val Sim = stroke("sim") {
        moveTo(6f, 3.5f); lineTo(14.5f, 3.5f); lineTo(18f, 7f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(9f, 11f); lineTo(15f, 11f); lineTo(15f, 17f); lineTo(9f, 17f); close()
    }

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

    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()
}

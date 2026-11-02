package com.chalaiden.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import kotlin.random.Random

private const val QR_MODULES = 21

/**
 * A bare-bones drawing of the bKash stand at a shop counter: header, QR panel, and the
 * merchant number printed under it. Blue brackets around the number show what to aim at.
 */
@Composable
fun QrStand(modifier: Modifier = Modifier) {
    val t = tokens
    val modules = remember { qrModules() }
    Column(
        modifier
            .width(132.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
            .background(t.raised)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)
            .semantics { contentDescription = "A shop's bKash stand. Aim at the number under the QR code." },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Brand header on the real stand: logo and a strapline, here just two bars.
        Box(Modifier.width(54.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.line))
        Spacer(Modifier.height(5.dp))
        Box(Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.line))
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val cell = size.width / QR_MODULES
                for (y in 0 until QR_MODULES) for (x in 0 until QR_MODULES) {
                    if (modules[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
            Spacer(Modifier.height(8.dp))
            AimedAt(t.accent) {
                Text(
                    "01x xxxx xxxx",
                    style = Type.label.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
                    color = Color.Black,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Viewfinder corner brackets drawn around [content]. */
@Composable
private fun AimedAt(color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.drawWithContent {
            drawContent()
            val len = 6.dp.toPx()
            val w = 1.6.dp.toPx()
            listOf(
                Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
                Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f),
            ).forEach { (o, d) ->
                drawLine(color, o, Offset(o.x + d.x * len, o.y), w, StrokeCap.Round)
                drawLine(color, o, Offset(o.x, o.y + d.y * len), w, StrokeCap.Round)
            }
        },
    ) { content() }
}

/** A plausible 21×21 QR: three finder squares, timing lines, and fixed-seed noise. Not scannable. */
private fun qrModules(): Array<BooleanArray> {
    val n = QR_MODULES
    val grid = Array(n) { BooleanArray(n) }
    val reserved = Array(n) { BooleanArray(n) }
    fun finder(ox: Int, oy: Int) {
        for (y in -1..7) for (x in -1..7) {
            val gx = ox + x
            val gy = oy + y
            if (gx !in 0 until n || gy !in 0 until n) continue
            reserved[gy][gx] = true
            val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
            grid[gy][gx] = x in 0..6 && y in 0..6 && ring != 2
        }
    }
    finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
    for (i in 8 until n - 8) {
        grid[6][i] = i % 2 == 0; reserved[6][i] = true
        grid[i][6] = i % 2 == 0; reserved[i][6] = true
    }
    val noise = Random(247)
    for (y in 0 until n) for (x in 0 until n) if (!reserved[y][x]) grid[y][x] = noise.nextFloat() < 0.47f
    return grid
}

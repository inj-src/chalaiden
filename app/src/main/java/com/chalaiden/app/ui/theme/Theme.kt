package com.chalaiden.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chalaiden.app.R

/** Brand tokens that Material's scheme has no slot for. */
@Immutable
data class Tokens(
    val paper: Color,
    val ink: Color,
    val inkSoft: Color,
    val teal: Color,
    /** Teal for text links; lighter in dark mode so it stays readable. */
    val link: Color,
    val onTeal: Color,
    val marigold: Color,
    val onMarigold: Color,
    val line: Color,
    val tile: Color,
    val error: Color,
)

private val Light = Tokens(
    paper = Color(0xFFF5F8F7),
    ink = Color(0xFF0F2E2B),
    inkSoft = Color(0xFF52706C),
    teal = Color(0xFF0E5E57),
    link = Color(0xFF0E5E57),
    onTeal = Color(0xFFF2FBF9),
    marigold = Color(0xFFF2A93B),
    onMarigold = Color(0xFF2B1A00),
    line = Color(0xFFC9D6D3),
    tile = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

private val Dark = Tokens(
    paper = Color(0xFF0C1B1A),
    ink = Color(0xFFE4EFED),
    inkSoft = Color(0xFF9BB5B1),
    teal = Color(0xFF1F7A71),
    link = Color(0xFF6FCDC1),
    onTeal = Color(0xFFF2FBF9),
    marigold = Color(0xFFF4B65A),
    onMarigold = Color(0xFF2B1A00),
    line = Color(0xFF2A4441),
    tile = Color(0xFF142826),
    error = Color(0xFFF2B8B5),
)

val LocalTokens = staticCompositionLocalOf { Light }

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, opticalSize: Float) = Font(
    R.font.bricolage,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

// One family, Bricolage Grotesque, cut at three optical sizes: sturdy for UI text,
// tighter for headlines, refined for the big numbers people check before paying.
private val TextFace = FontFamily(bricolage(400, 14f), bricolage(500, 14f), bricolage(600, 18f), bricolage(700, 18f))
private val DisplayFace = FontFamily(bricolage(600, 48f), bricolage(700, 48f))
private val NumberFace = FontFamily(bricolage(500, 96f), bricolage(600, 96f), bricolage(700, 96f))

object Type {
    val digit = TextStyle(fontFamily = NumberFace, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
    val amount = TextStyle(fontFamily = NumberFace, fontWeight = FontWeight.Bold, fontSize = 60.sp, letterSpacing = (-0.6).sp)
    val wordmark = TextStyle(fontFamily = DisplayFace, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp)
    val display = TextStyle(fontFamily = DisplayFace, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = TextFace, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp)
    val body = TextStyle(fontFamily = TextFace, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val label = TextStyle(fontFamily = TextFace, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp)
    val button = TextStyle(fontFamily = TextFace, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}

@Composable
fun ChalaidenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = t.teal, onPrimary = t.onTeal, background = t.paper, onBackground = t.ink,
            surface = t.paper, onSurface = t.ink, error = t.error, outline = t.line)
    } else {
        lightColorScheme(primary = t.teal, onPrimary = t.onTeal, background = t.paper, onBackground = t.ink,
            surface = t.paper, onSurface = t.ink, error = t.error, outline = t.line)
    }
    val typography = Typography(bodyLarge = Type.body, bodyMedium = Type.label, titleLarge = Type.title, labelLarge = Type.button)
    androidx.compose.runtime.CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

val tokens: Tokens @Composable get() = LocalTokens.current

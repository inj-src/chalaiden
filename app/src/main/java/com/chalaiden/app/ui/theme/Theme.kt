package com.chalaiden.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

/**
 * Dark only: black canvas, graphite surfaces, white for the main action.
 * Blue is the single accent, kept for money moments (caret, hold to pay, success).
 */
@Immutable
data class Tokens(
    val bg: Color,
    /** Cards, the number rail, sheets. */
    val surface: Color,
    /** Round action buttons and chips sitting on [bg] or [surface]. */
    val raised: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
)

private val Palette = Tokens(
    bg = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    raised = Color(0xFF2C2C2F),
    ink = Color(0xFFFFFFFF),
    inkSoft = Color(0xFF9A9AA1),
    line = Color(0xFF3A3A3D),
    accent = Color(0xFF3D7EFF),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFFF6B5E),
)

val LocalTokens = staticCompositionLocalOf { Palette }

@OptIn(ExperimentalTextApi::class)
private fun geist(weight: Int) = Font(
    R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

object Type {
    val digit = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 30.sp)
    val amount = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 56.sp, letterSpacing = (-1.2).sp)
    val wordmark = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.5).sp)
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

@Composable
fun ChalaidenTheme(content: @Composable () -> Unit) {
    val t = Palette
    val scheme = darkColorScheme(
        primary = t.ink, onPrimary = t.bg, background = t.bg, onBackground = t.ink,
        surface = t.bg, onSurface = t.ink, error = t.error, outline = t.line,
    )
    val typography = Typography(bodyLarge = Type.body, bodyMedium = Type.label, titleLarge = Type.title, labelLarge = Type.button)
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

val tokens: Tokens @Composable get() = LocalTokens.current

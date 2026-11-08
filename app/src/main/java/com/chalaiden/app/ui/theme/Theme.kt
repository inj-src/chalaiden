package com.chalaiden.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
 * Two palettes that follow the phone's theme. Dark: black canvas, graphite surfaces, white for the main
 * action. Light: white canvas, cool grey surfaces, near-black for the main action. Blue is the single
 * accent in both, kept for money moments (caret, hold to pay, success) and links.
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
    /** A disc behind an [error] glyph: "bKash turned this down". */
    val errorSoft: Color,
    /** "We couldn't tell": amber, never blue, so it can't be mistaken for a payment that went through. */
    val warn: Color,
    val warnSoft: Color,
    val isDark: Boolean,
)

private val DarkPalette = Tokens(
    bg = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    raised = Color(0xFF2C2C2F),
    ink = Color(0xFFFFFFFF),
    inkSoft = Color(0xFF9A9AA1),
    line = Color(0xFF3A3A3D),
    accent = Color(0xFF3D7EFF),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFFF6B5E),
    errorSoft = Color(0xFF45201C),
    warn = Color(0xFFFFC266),
    warnSoft = Color(0xFF3D2C08),
    isDark = true,
)

/** Every text colour here clears 4.5:1 on [Tokens.bg] and [Tokens.surface]; the accent is a shade deeper than dark's for that. */
private val LightPalette = Tokens(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F5),
    raised = Color(0xFFE7E7EC),
    ink = Color(0xFF0B0B0D),
    inkSoft = Color(0xFF66666E),
    line = Color(0xFFD3D3DA),
    accent = Color(0xFF2667F0),
    onAccent = Color(0xFFFFFFFF),
    error = Color(0xFFD33A2C),
    errorSoft = Color(0xFFFBE4E1),
    warn = Color(0xFFA35A00),
    warnSoft = Color(0xFFFFF1D6),
    isDark = false,
)

val LocalTokens = staticCompositionLocalOf { DarkPalette }

@OptIn(ExperimentalTextApi::class)
private fun geist(weight: Int) = Font(
    R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

object Type {
    val digit = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 30.sp, fontFeatureSettings = "tnum")
    val amount = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 56.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = "tnum")
    val wordmark = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.5).sp)
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

@Composable
fun ChalaidenTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.ink, onPrimary = t.bg, background = t.bg, onBackground = t.ink,
            surface = t.bg, onSurface = t.ink, error = t.error, outline = t.line,
        )
    } else {
        lightColorScheme(
            primary = t.ink, onPrimary = t.bg, background = t.bg, onBackground = t.ink,
            surface = t.bg, onSurface = t.ink, error = t.error, outline = t.line,
        )
    }
    val typography = Typography(bodyLarge = Type.body, bodyMedium = Type.label, titleLarge = Type.title, labelLarge = Type.button)
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

val tokens: Tokens @Composable get() = LocalTokens.current

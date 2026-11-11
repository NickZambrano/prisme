package com.fluxfr.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.fluxfr.tv.R

/** Jetons de couleur de l'appli : fond très sombre, accent bleu → violet. */
object Palette {
    val Bg = Color(0xFF07080C)
    val Rail = Color(0xFF0B0D13)
    val Card = Color(0xFF151821)
    val CardHigh = Color(0xFF1D212D)
    val Focused = Color(0xFF262B3A)
    val Selected = Color(0xFF232B4D)
    val Stroke = Color(0xFF262A36)
    val Accent = Color(0xFF378ADD)
    val Accent2 = Color(0xFF7F77DD)
    val Dim = Color(0xFF9AA0AE)
    val Faint = Color(0xFF8A90A0)
    val Gold = Color(0xFFF2C14E)
    val Error = Color(0xFFFF6B6B)
    val Success = Color(0xFF3DDC97)

    val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))
    val BgBrush = Brush.verticalGradient(listOf(Color(0xFF0D1020), Bg))
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun FluxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Card,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        typography = Typography().withFont(Poppins),
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Poppins, color = Color.White),
            content = content,
        )
    }
}

package se.pusslet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Tokens documented in docs/DESIGN.md. Sage (salvia) primary, oat (havre) secondary, terracotta accent.
internal object PussletPalette {
    val Sage10 = Color(0xFF0F2415)
    val Sage30 = Color(0xFF354F3B)
    val Sage40 = Color(0xFF4A6851)
    val Sage80 = Color(0xFFAFCFB4)
    val Sage90 = Color(0xFFCDE8D1)

    val Oat10 = Color(0xFF241A0C)
    val Oat30 = Color(0xFF54442F)
    val Oat40 = Color(0xFF6C5B44)
    val Oat80 = Color(0xFFD9C4A4)
    val Oat90 = Color(0xFFF4E2C6)

    val Terracotta10 = Color(0xFF390C00)
    val Terracotta30 = Color(0xFF7B2E16)
    val Terracotta40 = Color(0xFF99442A)
    val Terracotta80 = Color(0xFFFFB59D)
    val Terracotta90 = Color(0xFFFFDBD0)

    val Error30 = Color(0xFF8C1D18)
    val Error40 = Color(0xFFB3261E)
    val Error80 = Color(0xFFF2B8B5)
    val Error90 = Color(0xFFF9DEDC)

    val Neutral10 = Color(0xFF1A1C18)
    val Neutral12 = Color(0xFF1E201C)
    val Neutral17 = Color(0xFF282A25)
    val Neutral22 = Color(0xFF33352F)
    val Neutral90 = Color(0xFFE4E2DB)
    val Neutral94 = Color(0xFFF1EEE7)
    val Neutral96 = Color(0xFFF6F3EC)
    val Neutral98 = Color(0xFFFBF8F3)
    val NeutralVariant30 = Color(0xFF45483F)
    val NeutralVariant50 = Color(0xFF75786E)
    val NeutralVariant60 = Color(0xFF8F9287)
    val NeutralVariant80 = Color(0xFFC5C8BC)
    val NeutralVariant90 = Color(0xFFE1E4D8)
    val Ink = Color(0xFF1F1C17)
}

internal val LightColors: ColorScheme = with(PussletPalette) {
    lightColorScheme(
        primary = Sage40,
        onPrimary = Color.White,
        primaryContainer = Sage90,
        onPrimaryContainer = Sage10,
        secondary = Oat40,
        onSecondary = Color.White,
        secondaryContainer = Oat90,
        onSecondaryContainer = Oat10,
        tertiary = Terracotta40,
        onTertiary = Color.White,
        tertiaryContainer = Terracotta90,
        onTertiaryContainer = Terracotta10,
        error = Error40,
        onError = Color.White,
        errorContainer = Error90,
        onErrorContainer = Color(0xFF410E0B),
        background = Neutral98,
        onBackground = Ink,
        surface = Neutral98,
        onSurface = Ink,
        surfaceVariant = NeutralVariant90,
        onSurfaceVariant = NeutralVariant30,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Neutral96,
        surfaceContainer = Neutral94,
        surfaceContainerHigh = Color(0xFFEBE8E1),
        surfaceContainerHighest = Neutral90,
        outline = NeutralVariant50,
        outlineVariant = NeutralVariant80,
        inverseSurface = Color(0xFF30312C),
        inverseOnSurface = Neutral94,
        inversePrimary = Sage80,
        scrim = Color.Black,
    )
}

internal val DarkColors: ColorScheme = with(PussletPalette) {
    darkColorScheme(
        primary = Sage80,
        onPrimary = Color(0xFF1B3723),
        primaryContainer = Sage30,
        onPrimaryContainer = Sage90,
        secondary = Oat80,
        onSecondary = Color(0xFF3B2E1A),
        secondaryContainer = Oat30,
        onSecondaryContainer = Oat90,
        tertiary = Terracotta80,
        onTertiary = Color(0xFF5C1A05),
        tertiaryContainer = Terracotta30,
        onTertiaryContainer = Terracotta90,
        error = Error80,
        onError = Color(0xFF601410),
        errorContainer = Error30,
        onErrorContainer = Error90,
        background = Neutral10,
        onBackground = Neutral90,
        surface = Neutral10,
        onSurface = Neutral90,
        surfaceVariant = NeutralVariant30,
        onSurfaceVariant = NeutralVariant80,
        surfaceContainerLowest = Color(0xFF0F100D),
        surfaceContainerLow = Neutral12,
        surfaceContainer = Color(0xFF22241F),
        surfaceContainerHigh = Neutral17,
        surfaceContainerHighest = Neutral22,
        outline = NeutralVariant60,
        outlineVariant = NeutralVariant30,
        inverseSurface = Neutral90,
        inverseOnSurface = Color(0xFF2F312C),
        inversePrimary = Sage40,
        scrim = Color.Black,
    )
}

package se.pusslet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import se.pusslet.core.model.MemberColor

enum class ThemeMode { System, Light, Dark }

@Immutable
data class MemberColors(val container: Color, val content: Color)

@Immutable
data class PussletExtraColors(val isDark: Boolean) {
    fun member(color: MemberColor): MemberColors = if (isDark) {
        MemberColors(Color(color.darkContainer), Color(color.darkContent))
    } else {
        MemberColors(Color(color.lightContainer), Color(color.lightContent))
    }
}

val LocalPussletColors = staticCompositionLocalOf { PussletExtraColors(isDark = false) }

/**
 * App theme. [dynamicColor] opts into wallpaper colours on Android 12+. It is off by default:
 * the calm sage/oat palette is part of the product.
 */
@Composable
fun PussletTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme: ColorScheme = (if (dynamicColor) platformDynamicColorScheme(dark) else null)
        ?: if (dark) DarkColors else LightColors

    CompositionLocalProvider(LocalPussletColors provides PussletExtraColors(isDark = dark)) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PussletTypography,
            shapes = PussletShapes,
            content = content,
        )
    }
}

object PussletThemeExtras {
    val colors: PussletExtraColors
        @Composable @ReadOnlyComposable
        get() = LocalPussletColors.current
}

/** Returns the platform's dynamic (wallpaper) colour scheme, or null when unsupported. */
@Composable
internal expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

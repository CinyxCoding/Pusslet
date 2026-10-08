package se.pusslet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS has no wallpaper-based colour scheme. */
@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

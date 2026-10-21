package se.pusslet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import se.pusslet.core.model.Contrast
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG 2.2 contrast for the app's own colour schemes (dynamic colour is the system's): 4.5:1 for text on its background
 * (1.4.3) and 3:1 for the outlines and icons that tell controls apart (1.4.11).
 */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color) = Contrast.ratio(a.toArgb().toLong() and ARGB, b.toArgb().toLong() and ARGB)

    private fun ColorScheme.surfaces() = listOf(
        "surface" to surface,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceVariant" to surfaceVariant,
    )

    private fun problems(name: String, s: ColorScheme): List<String> {
        val text = listOf(
            Triple("onPrimary", s.onPrimary, "primary" to s.primary),
            Triple("onPrimaryContainer", s.onPrimaryContainer, "primaryContainer" to s.primaryContainer),
            Triple("onSecondary", s.onSecondary, "secondary" to s.secondary),
            Triple("onSecondaryContainer", s.onSecondaryContainer, "secondaryContainer" to s.secondaryContainer),
            Triple("onTertiary", s.onTertiary, "tertiary" to s.tertiary),
            Triple("onTertiaryContainer", s.onTertiaryContainer, "tertiaryContainer" to s.tertiaryContainer),
            Triple("onError", s.onError, "error" to s.error),
            Triple("onErrorContainer", s.onErrorContainer, "errorContainer" to s.errorContainer),
            Triple("onBackground", s.onBackground, "background" to s.background),
            Triple("inverseOnSurface", s.inverseOnSurface, "inverseSurface" to s.inverseSurface),
        ) + s.surfaces().flatMap { bg ->
            listOf(
                Triple("onSurface", s.onSurface, bg),
                Triple("onSurfaceVariant", s.onSurfaceVariant, bg),
                // Links, text buttons and error text sit straight on the surfaces.
                Triple("primary", s.primary, bg),
                Triple("error", s.error, bg),
            )
        }
        val textProblems = text.mapNotNull { (fg, color, bg) ->
            val r = ratio(color, bg.second)
            "$name: $fg on ${bg.first} is ${fmt(r)}:1, needs 4.5".takeIf { r < TEXT }
        }
        val controls = s.surfaces().flatMap { (bg, color) ->
            listOf("outline" to s.outline, "primary" to s.primary).mapNotNull { (fg, c) ->
                val r = ratio(c, color)
                "$name: $fg on $bg is ${fmt(r)}:1, needs 3".takeIf { r < NON_TEXT }
            }
        }
        return textProblems + controls
    }

    private fun fmt(r: Double) = ((r * 100).toInt() / 100.0).toString()

    @Test
    fun lightAndDarkReadWell() {
        val problems = problems("light", LightColors) + problems("dark", DarkColors)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
        const val ARGB = 0xFFFFFFFFL
    }
}

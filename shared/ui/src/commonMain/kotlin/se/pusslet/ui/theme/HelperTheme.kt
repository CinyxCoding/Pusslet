package se.pusslet.ui.theme

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The helper view's type scale (UX plan Steg 3, ADR-029): nothing below 18 sp. The date is 28/36, body text 20/28 and
 * buttons 18/24, so the shared components (`CheckRow`, `NavRow`, `SentenceRow`) get larger without special code. All in
 * sp, so the system text size still applies on top.
 */
val HelperTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.Normal),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Normal),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Normal),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
        // TabHeader's title: the date ("Torsdag 8 oktober").
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
        // Section and sheet headings, the top bar.
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
        bodyLarge = bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodyMedium = bodyMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodySmall = bodySmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    )
}

/** "Du hämtar Simon 15:30": a mission in the helper view, 22/30 Medium (the child's name and the time in SemiBold). */
val HelperMissionStyle: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)

/** Sizes of the helper view: every button and row at least 64 dp, with at least 12 dp between touch targets. */
object HelperDimens {
    /** Ja/Nej, "Jag kan inte idag": full width, stacked. */
    val button = 64.dp

    /** Rows that open or toggle something. */
    val row = 64.dp

    /** Between two touch targets. */
    val gap = 12.dp

    /** Material's own minimum for checkboxes, switches and the back arrow. */
    val minimumInteractive = 56.dp
}

/**
 * Wraps the whole helper view (`HelperShell`): the colours and shapes of [PussletTheme] around it, with [HelperTypography]
 * and a larger minimum touch target for Material's own controls.
 */
@Composable
fun HelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = HelperTypography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides HelperDimens.minimumInteractive, content = content)
    }
}

package se.pusslet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 type scale tuned for glanceability: slightly larger body text, calmer headline weights.
 * All sizes are in sp so system font scaling (up to 200 %) applies.
 */
internal val PussletTypography: Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Normal),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Normal),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

/** Extra-large item style used by store mode (butiksläge). */
val StoreModeItemStyle: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)

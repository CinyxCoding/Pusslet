package se.pusslet.feature.today.helper

import androidx.compose.ui.unit.dp
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.HelperMissionStyle
import se.pusslet.ui.theme.HelperTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The helper view's sizes (UX plan Steg 3, acceptance 4): nothing below 18 sp, buttons and rows at least 64 dp. */
class HelperThemeTest {
    @Test
    fun noTextStyleIsBelowEighteenSp() {
        val t = HelperTypography
        val styles = mapOf(
            "displayLarge" to t.displayLarge,
            "displayMedium" to t.displayMedium,
            "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge,
            "headlineMedium" to t.headlineMedium,
            "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge,
            "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall,
            "mission" to HelperMissionStyle,
        )
        val small = styles.filterValues { it.fontSize.value < MIN_SP }.keys
        assertEquals(emptySet(), small)
    }

    @Test
    fun theDateMissionsBodyAndButtonsHaveTheirSizes() {
        assertEquals(28f, HelperTypography.headlineSmall.fontSize.value)
        assertEquals(22f, HelperMissionStyle.fontSize.value)
        assertEquals(20f, HelperTypography.bodyLarge.fontSize.value)
        assertEquals(18f, HelperTypography.labelLarge.fontSize.value)
    }

    @Test
    fun touchTargetsAreLarge() {
        assertTrue(HelperDimens.button >= 64.dp)
        assertTrue(HelperDimens.row >= 64.dp)
        assertTrue(HelperDimens.minimumInteractive >= 56.dp)
        assertTrue(HelperDimens.gap >= 12.dp)
    }

    private companion object {
        const val MIN_SP = 18f
    }
}

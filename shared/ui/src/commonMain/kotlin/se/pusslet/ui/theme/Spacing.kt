package se.pusslet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 4 dp spacing grid (docs/DESIGN.md). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screenHorizontal = 16.dp

    /** Minimum touch target. Primary actions get a bit more ("en hand, en tumme"). */
    val touchTarget = 48.dp
    val primaryTouchTarget = 56.dp

    /** Bottom padding for tab-root lists so the last row is never under the round + (ADR-023). */
    val listBottomInset = 88.dp

    /** Air between two flat `Section`s; sections are separated by space, not by cards or dividers. */
    val sectionGap = 32.dp
}

internal val PussletShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

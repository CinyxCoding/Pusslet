package se.pusslet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import se.pusslet.ui.theme.Spacing

/**
 * A tinted, non-interactive label ("Vecka 41", "Alla helgons dag", a tag). It informs and never looks like a button:
 * no border, no ripple, no click. [selected] uses the sage "valt/idag" colour, e.g. the "Idag" pill on today's card.
 */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

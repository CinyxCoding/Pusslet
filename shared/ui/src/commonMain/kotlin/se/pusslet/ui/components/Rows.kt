package se.pusslet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.pusslet.ui.theme.Spacing

/**
 * One sentence per row, person first: "Johan lämnar Simon 07:50", "Ikväll: Fiskgratäng". The answer rows of the focus surface
 * ([HeroCard]) and of the helper view.
 *
 * [emphasized] is for the viewer's own rows ("Du hämtar Ella 15:45"): SemiBold in sage, the colour of "Du". [supporting] is
 * at most one quiet line with at most three parts (" · "). With [onClick] the row shows a chevron (unless [trailing] is
 * given) and [onClickLabel] tells TalkBack what a double tap does ("Öppna"). The row is one TalkBack node; a button in
 * [trailing] ("Jag tar det") stays its own node.
 */
@Composable
fun SentenceRow(
    text: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    emphasized: Boolean = false,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .then(interaction)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (leadingIcon != null) RowIcon(leadingIcon)
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized) FontWeight.SemiBold else null,
                color = if (emphasized) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Chevron()
        }
    }
}

/**
 * A full-width row that OPENS something: "Anpassa Idag ›", "Näring för veckan ›", the rows in Mer. Always with a chevron and
 * at least 56 dp tall. Use a button for rows that DO something and [OutlinedValueField] for rows that choose a value.
 */
@Composable
fun NavRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.primaryTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (icon != null) RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Chevron()
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
}

/** "Opens something" (›). Decorative: the row's role and text carry the meaning for TalkBack. */
@Composable
private fun Chevron() {
    Icon(PussletIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

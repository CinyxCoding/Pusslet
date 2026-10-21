package se.pusslet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.pusslet.ui.theme.Spacing

/**
 * A flat section ("Idag händer", "Kommande"): a `titleLarge` heading with an optional icon and action, the content below.
 *
 * Put `Spacing.sectionGap` between two flat sections (for example `Arrangement.spacedBy(Spacing.sectionGap)`), the section
 * adds no outer space itself. With [framed] the section is its own card ([SectionFrame]), for a screen of several parts
 * that should read as clearly separate (Idag); framed sections only need the list's normal gap. [action] is a quiet
 * `TextButton` such as "Visa alla". With a null [title] only the content is drawn. In a lazy list where every row is its
 * own item, use [SectionHeader] for the heading instead.
 */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
    framed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (title != null) {
                SectionHeader(title, icon = icon, action = action)
            } else if (action != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
            }
            content()
        }
    }
    if (framed) SectionFrame(modifier) { body(Modifier) } else body(modifier)
}

/**
 * A quiet card around one part of a screen: the low container colour with a thin outline, so it stands apart from the
 * background in light and dark without competing with the focus surface ([HeroCard], oat).
 */
@Composable
fun SectionFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow, contentColor = colors.onSurface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), content = content)
    }
}

/**
 * The heading row of a [Section] on its own, for lazy lists where the section's rows are separate items. TalkBack can jump
 * between these headings.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/**
 * The one focus surface of a screen (ADR-032): havre `secondaryContainer`, for the answer the screen exists for, such as the
 * morning check on Idag with [SentenceRow]s. At most one per screen and never for lists, reminders or problems.
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer),
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content,
        )
    }
}

/**
 * A quiet, centred line for "nothing (more) here" inside a screen that has other content: "Inget mer idag". A screen that
 * is empty altogether uses [EmptyState] with one action instead.
 */
@Composable
fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.lg),
    )
}

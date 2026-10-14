package se.pusslet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.calendar_go_today
import se.pusslet.ui.resources.calendar_next
import se.pusslet.ui.resources.calendar_previous
import se.pusslet.ui.resources.today
import se.pusslet.ui.theme.Spacing

/**
 * The heading of a tab root or a full screen without a top bar: "Torsdag 8 oktober" with the subtitle "Vecka 41".
 *
 * Use it once per screen, at the top. The title is `headlineSmall` (the top of the type scale) and TalkBack reads title and
 * subtitle as one heading. [actions] holds at most two icon buttons (share, filter); everything rarer belongs in a ⋮ menu.
 * The caller adds the screen gutter (`Spacing.screenHorizontal`).
 */
@Composable
fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * Steps through weeks: "Vecka 41 · oktober" on the left, ‹ › on the right. Kalender and Mat use the same switcher so the two
 * headers look alike.
 *
 * Pass [onToday] only while another week is showing; the "Idag" button then appears to the left of the arrows, so the arrows
 * never move. The title is a polite live region, so TalkBack reads the new week after ‹ or ›. The caller adds the gutter.
 */
@Composable
fun WeekSwitcher(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onToday != null) {
            val goToday = stringResource(Res.string.calendar_go_today)
            TextButton(onClick = onToday, modifier = Modifier.semantics { contentDescription = goToday }) {
                Text(stringResource(Res.string.today))
            }
        }
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.calendar_previous))
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.calendar_next))
        }
    }
}

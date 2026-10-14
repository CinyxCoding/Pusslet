package se.pusslet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class MoreDestination { Family, Tasks, Settings, Account, AboutData, Privacy }

private data class MoreItem(val dest: MoreDestination, val title: StringResource, val icon: ImageVector)

/** The Mer tab: one full-width row with a chevron per destination, no subtitles and no + (UX plan 1.12). */
@Composable
fun MoreScreen(onOpen: (MoreDestination) -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    // "Uppgifter och sysslor" (MoreDestination.Tasks) stays hidden until the tasks phase is done; its route is kept.
    val rows = listOf(
        MoreItem(MoreDestination.Family, Res.string.more_family, PussletIcons.Family),
        MoreItem(MoreDestination.Settings, Res.string.more_settings, Icons.Filled.Settings),
        MoreItem(MoreDestination.Account, Res.string.more_account, Icons.Filled.AccountCircle),
        MoreItem(MoreDestination.AboutData, Res.string.more_about_data, Icons.Filled.Info),
        MoreItem(MoreDestination.Privacy, Res.string.more_privacy, Icons.Filled.Lock),
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = Spacing.lg + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl + contentPadding.calculateBottomPadding(),
        ),
    ) {
        item {
            Text(
                stringResource(Res.string.more_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.md)
                    .semantics { heading() },
            )
        }
        items(rows, key = { it.dest.name }) { row ->
            ListRow(
                title = stringResource(row.title),
                leading = { Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailing = {
                    Icon(PussletIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                // Full width: the whole row is the touch target, the screen margin sits inside it.
                modifier = Modifier
                    .clickable(role = Role.Button) { onOpen(row.dest) }
                    .padding(horizontal = Spacing.screenHorizontal),
            )
        }
    }
}

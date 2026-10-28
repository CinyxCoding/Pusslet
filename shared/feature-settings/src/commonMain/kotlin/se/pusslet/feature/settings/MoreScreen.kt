package se.pusslet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class MoreDestination(val parentsOnly: Boolean = false) {
    Search,
    Family,
    Tasks,
    Kids(parentsOnly = true),
    KidMode(parentsOnly = true),
    Medicine(parentsOnly = true),
    SharedCosts(parentsOnly = true),
    Breaks(parentsOnly = true),
    Settings,
    Account,
    AboutData,
    Privacy,
}

private data class MoreItem(val dest: MoreDestination, val title: StringResource, val icon: ImageVector)

/**
 * The Mer tab (UX plan 1.12 and Steg 2): two groups – the family and everyday life, then the app with "Om Pusslet" last –
 * of full-width rows with a chevron, no subtitles and no +.
 *
 * [onSimpleView] is only passed for a caregiver who chose the full app: "Enkel vy" then comes first and switches back to
 * the helper view (UX plan Steg 3, ADR-029).
 */
@Composable
fun MoreScreen(onOpen: (MoreDestination) -> Unit, contentPadding: PaddingValues = PaddingValues(), onSimpleView: (() -> Unit)? = null) {
    val groups = listOf(
        Res.string.more_group_family to listOf(
            MoreItem(MoreDestination.Search, Res.string.search_title, Icons.Filled.Search),
            MoreItem(MoreDestination.Family, Res.string.more_family, PussletIcons.Family),
            MoreItem(MoreDestination.Tasks, Res.string.more_tasks, PussletIcons.Task),
            MoreItem(MoreDestination.Kids, Res.string.kids_title, Icons.Filled.Star),
            MoreItem(MoreDestination.KidMode, Res.string.kid_mode, Icons.Filled.Face),
            MoreItem(MoreDestination.Breaks, Res.string.breaks_title, PussletIcons.Beach),
            MoreItem(MoreDestination.Medicine, Res.string.medicine_title, PussletIcons.Medication),
            MoreItem(MoreDestination.SharedCosts, Res.string.costs_title, PussletIcons.Payments),
            MoreItem(MoreDestination.Account, Res.string.more_account, Icons.Filled.AccountCircle),
        ),
        Res.string.more_group_app to listOf(
            MoreItem(MoreDestination.Settings, Res.string.more_settings, Icons.Filled.Settings),
            MoreItem(MoreDestination.Privacy, Res.string.more_privacy, Icons.Filled.Lock),
            MoreItem(MoreDestination.AboutData, Res.string.more_about_data, Icons.Filled.Info),
        ),
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
        if (onSimpleView != null) {
            item(key = "simple-view") {
                ListRow(
                    title = stringResource(Res.string.helper_simple_view),
                    leading = { Icon(PussletIcons.Sun, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailing = {
                        Icon(PussletIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onSimpleView)
                        .padding(horizontal = Spacing.screenHorizontal),
                )
            }
        }
        groups.forEach { (title, rows) ->
            item(key = "group-${rows.first().dest.name}") {
                SectionHeader(
                    stringResource(title),
                    Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.lg),
                )
            }
            // A caregiver who chose the full app does not see the parents' own pages (the medicine log, ADR-050).
            items(rows.filter { !it.dest.parentsOnly || onSimpleView == null }, key = { it.dest.name }) { row -> MoreRow(row, onOpen) }
        }
    }
}

@Composable
private fun MoreRow(row: MoreItem, onOpen: (MoreDestination) -> Unit) {
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

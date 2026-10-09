package se.pusslet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

enum class MoreDestination { Search, Family, Tasks, Settings, Account, AboutData, Privacy }

private data class MoreItem(val dest: MoreDestination, val title: StringResource, val body: StringResource?, val icon: ImageVector)

@Composable
fun MoreScreen(onOpen: (MoreDestination) -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val items = listOf(
        MoreItem(MoreDestination.Search, Res.string.more_search, Res.string.more_search_body, Icons.Filled.Search),
        MoreItem(MoreDestination.Family, Res.string.more_family, Res.string.more_family_body, PussletIcons.Family),
        MoreItem(MoreDestination.Tasks, Res.string.more_tasks, Res.string.more_tasks_body, PussletIcons.Task),
        MoreItem(MoreDestination.Settings, Res.string.more_settings, null, Icons.Filled.Settings),
        MoreItem(MoreDestination.Account, Res.string.more_account, null, Icons.Filled.AccountCircle),
        MoreItem(MoreDestination.AboutData, Res.string.more_about_data, null, Icons.Filled.Info),
        MoreItem(MoreDestination.Privacy, Res.string.more_privacy, null, Icons.Filled.Lock),
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.lg + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Text(
                stringResource(Res.string.more_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics {
                    heading()
                },
            )
        }
        item {
            SectionCard(null) {
                items.forEach { item ->
                    ListRow(
                        title = stringResource(item.title),
                        supporting = item.body?.let { stringResource(it) },
                        leading = { Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpen(item.dest) },
                    )
                }
            }
        }
    }
}

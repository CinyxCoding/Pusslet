package se.pusslet.feature.tasks.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.tasks.CatalogChore
import se.pusslet.core.tasks.ChoreGroup
import se.pusslet.core.tasks.ChoreOwner
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.chores_add_many
import se.pusslet.ui.resources.chores_add_one
import se.pusslet.ui.resources.chores_exists
import se.pusslet.ui.resources.chores_intro
import se.pusslet.ui.resources.chores_title
import se.pusslet.ui.resources.field_who
import se.pusslet.ui.resources.tasks_assignee_none
import se.pusslet.ui.resources.tasks_rotation_on
import se.pusslet.ui.theme.Spacing

/** Mer › Uppgifter › "Vanliga sysslor" (ADR-065). */
@Composable
fun ChoreCatalogRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: ChoreCatalogViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val titles = CatalogChore.entries.associateWith { stringResource(it.titleRes()) }
    ChoreCatalogScreen(
        state = state,
        titles = titles,
        actions = ChoreCatalogActions(
            onBack = onBack,
            onToggle = viewModel::toggle,
            onOwner = viewModel::setOwner,
            onAdd = { viewModel.add(titles) },
        ),
    )
}

/** What a tap on the list can do. The defaults do nothing, for previews and tests of the screen alone. */
data class ChoreCatalogActions(
    val onBack: () -> Unit = {},
    val onToggle: (CatalogChore) -> Unit = {},
    val onOwner: (ChoreOwner) -> Unit = {},
    val onAdd: () -> Unit = {},
)

/** The chores by group, each with how often it comes back; what the family already has is ticked and cannot be picked. */
@Composable
fun ChoreCatalogScreen(
    state: ChoreCatalogUiState,
    titles: Map<CatalogChore, String>,
    actions: ChoreCatalogActions = ChoreCatalogActions(),
) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.chores_title), onBack = actions.onBack) },
        bottomBar = { AddBar(state.toAdd(titles).size, actions.onAdd) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.md,
            ),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(Res.string.chores_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.parents.isNotEmpty()) item(key = "who") { OwnerChoice(state, actions.onOwner) }
            ChoreGroup.entries.forEach { group ->
                item(key = group.name) { SectionHeader(stringResource(group.titleRes()), Modifier.padding(top = Spacing.sectionGap)) }
                items(CatalogChore.entries.filter { it.group == group }, key = { it.name }) { chore ->
                    val title = titles[chore].orEmpty()
                    ChoreRow(
                        title = title,
                        repeat = stringResource(chore.repeatRes()),
                        picked = chore in state.picked,
                        exists = state.exists(title),
                        onToggle = { actions.onToggle(chore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddBar(count: Int, onAdd: () -> Unit) {
    val label = when (count) {
        0 -> stringResource(Res.string.action_add)
        1 -> stringResource(Res.string.chores_add_one)
        else -> stringResource(Res.string.chores_add_many, count)
    }
    // The list scrolls under the bar, so the bar has its own background.
    Surface(color = MaterialTheme.colorScheme.surface) {
        Button(
            onClick = onAdd,
            enabled = count > 0,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                .heightIn(min = Spacing.primaryTouchTarget),
        ) { Text(label) }
    }
}

/** "Vem": nobody yet, one of the parents, or the parents in turn (from two parents). */
@Composable
private fun OwnerChoice(state: ChoreCatalogUiState, onOwner: (ChoreOwner) -> Unit) {
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(Res.string.field_who), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OwnerChip(stringResource(Res.string.tasks_assignee_none), ChoreOwner.Nobody, state.owner, onOwner)
            state.parents.forEach { OwnerChip(it.name, ChoreOwner.Person(it.id), state.owner, onOwner) }
            if (state.parents.size > 1) {
                val turns = ChoreOwner.TakeTurns(state.parents.map { it.id })
                OwnerChip(stringResource(Res.string.tasks_rotation_on), turns, state.owner, onOwner)
            }
        }
    }
}

@Composable
private fun OwnerChip(label: String, owner: ChoreOwner, selected: ChoreOwner, onOwner: (ChoreOwner) -> Unit) {
    FilterChip(
        selected = owner == selected,
        onClick = { onOwner(owner) },
        label = { Text(label) },
        colors = SelectionColors.filterChip(),
    )
}

/** Not `CheckRow`: that strikes a ticked row through, as done, and here a tick means "we want this one". */
@Composable
private fun ChoreRow(title: String, repeat: String, picked: Boolean, exists: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .toggleable(value = picked || exists, enabled = !exists, role = Role.Checkbox) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Checkbox(checked = picked || exists, onCheckedChange = null, enabled = !exists)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (exists) secondary else Color.Unspecified)
            Text(
                if (exists) stringResource(Res.string.chores_exists) else repeat,
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}

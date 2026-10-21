package se.pusslet.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_edit
import se.pusslet.ui.resources.action_undo
import se.pusslet.ui.resources.more_tasks
import se.pusslet.ui.resources.state_collapsed
import se.pusslet.ui.resources.state_expanded
import se.pusslet.ui.resources.tasks_checklist_progress
import se.pusslet.ui.resources.tasks_deleted_receipt
import se.pusslet.ui.resources.tasks_distribution
import se.pusslet.ui.resources.tasks_done_receipt
import se.pusslet.ui.resources.tasks_empty
import se.pusslet.ui.resources.tasks_new
import se.pusslet.ui.resources.tasks_reopened_receipt
import se.pusslet.ui.resources.tasks_row_due
import se.pusslet.ui.resources.tasks_row_my_turn
import se.pusslet.ui.resources.tasks_row_on
import se.pusslet.ui.resources.tasks_row_turn
import se.pusslet.ui.resources.tasks_section_done
import se.pusslet.ui.resources.tasks_section_family
import se.pusslet.ui.resources.tasks_section_mine
import se.pusslet.ui.resources.tasks_section_unassigned
import se.pusslet.ui.resources.today_overdue
import se.pusslet.ui.theme.Spacing

/**
 * Mer › Uppgifter: the answer first (UX plan §6). A tick marks one occurrence and the receipt offers "Ångra"; the round +
 * opens the editor. Caregivers see only what is shared and can only tick.
 */
@Composable
fun TasksRoute(
    onBack: () -> Unit,
    onOpenTask: (taskId: String?) -> Unit,
    onOpenDistribution: () -> Unit,
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // "Dammsuga borttagen · Ångra" when the editor deleted a task and closed.
    val deleted = state.deleted
    LaunchedEffect(deleted) {
        if (deleted == null) return@LaunchedEffect
        val undo = snackbar.showUndo(getString(Res.string.tasks_deleted_receipt, deleted.title), getString(Res.string.action_undo))
        if (undo) viewModel.restoreDeleted(deleted) else viewModel.deletedShown(deleted)
    }

    fun receipt(title: String, done: Boolean, onUndo: () -> Unit) {
        scope.launch {
            val message = getString(if (done) Res.string.tasks_done_receipt else Res.string.tasks_reopened_receipt, title)
            if (snackbar.showUndo(message, getString(Res.string.action_undo))) onUndo()
        }
    }
    TasksScreen(
        state = state,
        snackbar = snackbar,
        actions = TasksActions(
            onBack = onBack,
            onAdd = { onOpenTask(null) },
            onOpen = { onOpenTask(it.task.id) },
            onToggle = { row, done ->
                viewModel.setDone(row.occurrence, done)?.let { undo -> receipt(row.task.title, done) { viewModel.undo(undo) } }
            },
            onReopen = { row ->
                viewModel.reopen(row)?.let { undo -> receipt(row.done.task.title, done = false) { viewModel.undo(undo) } }
            },
            onOpenDistribution = onOpenDistribution,
        ),
    )
}

/** What a tap on the task list can do. The defaults do nothing, for previews and screenshot tests of the screen alone. */
data class TasksActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onOpen: (TaskRow) -> Unit = {},
    val onToggle: (TaskRow, Boolean) -> Unit = { _, _ -> },
    val onReopen: (DoneRow) -> Unit = {},
    val onOpenDistribution: () -> Unit = {},
)

@Composable
fun TasksScreen(
    state: TasksUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: TasksActions = TasksActions(),
) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.more_tasks), onBack = actions.onBack) },
        floatingActionButton = {
            if (state.canEdit) {
                // A sub page with its own round + ("Ny uppgift"), no text on the button (ADR-023).
                FloatingActionButton(onClick = actions.onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.tasks_new))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val today = state.today
        if (state.loading || today == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        var familyOpen by rememberSaveable { mutableStateOf(false) }
        var doneOpen by rememberSaveable { mutableStateOf(false) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.listBottomInset,
            ),
        ) {
            if (state.isEmpty) item(key = "empty") { EmptyState(message = stringResource(Res.string.tasks_empty)) }
            var first = true

            fun gap(): Modifier = if (first) Modifier.also { first = false } else Modifier.padding(top = Spacing.sectionGap)
            if (state.mine.isNotEmpty()) {
                val m = gap()
                item(key = "mine") { SectionHeader(stringResource(Res.string.tasks_section_mine), m) }
                taskRows(state.mine, today, state.canEdit, actions)
            }
            if (state.unassigned.isNotEmpty()) {
                val m = gap()
                item(key = "unassigned") { SectionHeader(stringResource(Res.string.tasks_section_unassigned), m) }
                taskRows(state.unassigned, today, state.canEdit, actions)
            }
            if (state.family.isNotEmpty()) {
                val m = gap()
                item(key = "family") {
                    FoldHeader(stringResource(Res.string.tasks_section_family, state.family.size), familyOpen, m) {
                        familyOpen = !familyOpen
                    }
                }
                if (familyOpen) taskRows(state.family, today, state.canEdit, actions)
            }
            if (state.done.isNotEmpty()) {
                val m = gap()
                item(key = "done") {
                    FoldHeader(stringResource(Res.string.tasks_section_done, state.done.size), doneOpen, m) { doneOpen = !doneOpen }
                }
                if (doneOpen) {
                    items(state.done, key = { "done-${it.done.key}" }) { row ->
                        CheckRow(
                            text = row.done.task.title,
                            checked = true,
                            onCheckedChange = { actions.onReopen(row) },
                            supporting = doneDetails(row, today),
                        )
                    }
                }
            }
            if (state.canEdit) {
                val m = gap()
                item(key = "distribution") { NavRow(stringResource(Res.string.tasks_distribution), actions.onOpenDistribution, m) }
            }
        }
    }
}

private fun LazyListScope.taskRows(rows: List<TaskRow>, today: LocalDate, canEdit: Boolean, actions: TasksActions) {
    items(rows, key = { it.key }) { row ->
        TaskRowItem(row, today, canEdit, onToggle = { actions.onToggle(row, it) }, onOpen = { actions.onOpen(row) })
    }
}

/** "Familjen (3) ⌄": a section that starts folded, with its state for TalkBack. */
@Composable
private fun FoldHeader(title: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val stateText = stringResource(if (expanded) Res.string.state_expanded else Res.string.state_collapsed)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                stateDescription = stateText
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One open task as a sentence with a checkbox (Role.Checkbox) that ticks this occurrence. For parents the text opens the
 * editor; for caregivers the whole row is the checkbox. Overdue is a reminder, in terracotta – never red.
 */
@Composable
private fun TaskRowItem(row: TaskRow, today: LocalDate, canEdit: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val done = row.occurrence.done
    val tick: (Boolean) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onToggle(it)
    }
    val title = row.task.title
    val wholeRow = if (canEdit) Modifier else Modifier.toggleable(value = done, role = Role.Checkbox, onValueChange = tick)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).then(wholeRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = if (canEdit) {
            Modifier
                .toggleable(value = done, role = Role.Checkbox, onValueChange = tick)
                .semantics { contentDescription = title }
        } else {
            Modifier
        }
        Box(box.size(Spacing.touchTarget), contentAlignment = Alignment.Center) { Checkbox(checked = done, onCheckedChange = null) }
        val open = if (canEdit) {
            Modifier.clickable(onClickLabel = stringResource(Res.string.action_edit), role = Role.Button, onClick = onOpen)
        } else {
            Modifier
        }
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = Spacing.touchTarget)
                .then(open)
                .padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                rowText(row, today),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.mine) FontWeight.SemiBold else null,
            )
            RowDetails(row, today)
        }
    }
}

/** At most one quiet line with at most three parts: "Försenad · lör · 2 av 5". */
@Composable
private fun RowDetails(row: TaskRow, today: LocalDate) {
    val date = row.occurrence.date
    val parts = buildList {
        // A rotating chore's sentence says the week; its day goes here.
        if (row.turnWeekOffset != null && date != null) add(dayLabel(date, today, row.task.dueTime))
        if (!row.mine && row.turnWeekOffset == null) row.assignee?.let { add(it.name) }
        if (row.checklistTotal > 0) add(stringResource(Res.string.tasks_checklist_progress, row.checklistDone, row.checklistTotal))
    }
    if (!row.overdue && parts.isEmpty()) return
    val overdue = stringResource(Res.string.today_overdue)
    val reminder = MaterialTheme.colorScheme.tertiary
    val text = buildAnnotatedString {
        if (row.overdue) withStyle(SpanStyle(color = reminder, fontWeight = FontWeight.Medium)) { append(overdue) }
        parts.take(if (row.overdue) 2 else MAX_PARTS).forEachIndexed { i, part ->
            if (i > 0 || row.overdue) append(SEPARATOR)
            append(part)
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * "Dammsuga – din tur den här veckan", "Dammsuga – Johan nästa vecka", "Anmäl Ella till sportlovsläger · senast fre",
 * "Läsa 20 minuter · idag".
 */
@Composable
private fun rowText(row: TaskRow, today: LocalDate): String {
    val title = row.task.title
    val offset = row.turnWeekOffset
    if (offset != null) {
        val week = weekPhrase(offset, row.turnWeek)
        return when {
            row.mine -> stringResource(Res.string.tasks_row_my_turn, title, week)
            row.assignee != null -> stringResource(Res.string.tasks_row_turn, title, row.assignee.name, week)
            else -> title
        }
    }
    val date = row.occurrence.date ?: return title
    val day = dayLabel(date, today, row.task.dueTime)
    return if (TaskScheduler.isRecurring(row.task)) {
        stringResource(Res.string.tasks_row_on, title, day)
    } else {
        stringResource(Res.string.tasks_row_due, title, day)
    }
}

/** "Sara · lör": who ticked it and the day. */
@Composable
private fun doneDetails(row: DoneRow, today: LocalDate): String? =
    listOfNotNull(row.by?.name, row.day?.let { dayLabel(it, today, null) }).joinToString(SEPARATOR).ifBlank { null }

private const val SEPARATOR = " · "
private const val MAX_PARTS = 3

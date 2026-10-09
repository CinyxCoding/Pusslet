package se.pusslet.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.PendingApproval
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.plusDays
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.relativeDay
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun TasksRoute(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onNew: (chore: Boolean) -> Unit,
    onWorkload: () -> Unit,
    onAllowance: () -> Unit = {},
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasksScreen(
        state,
        onBack,
        onEdit,
        onNew,
        onWorkload,
        viewModel::setFilter,
        viewModel::setDone,
        viewModel::quickAdd,
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
        onAllowance = onAllowance,
    )
}

@Composable
fun TasksScreen(
    state: TasksUiState,
    onBack: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onNew: (Boolean) -> Unit = {},
    onWorkload: () -> Unit = {},
    onFilter: (TaskFilter) -> Unit = {},
    onDone: (TaskOccurrence, Boolean) -> Unit = { _, _ -> },
    onQuickAdd: (String) -> Unit = {},
    onApprove: (PendingApproval) -> Unit = {},
    onReject: (PendingApproval) -> Unit = {},
    onAllowance: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            PussletTopBar(
                stringResource(Res.string.more_tasks),
                onBack = onBack,
                actions = { TextButton(onClick = onWorkload) { Text(stringResource(Res.string.workload_title)) } },
            )
        },
        floatingActionButton = {
            if (state.canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { onNew(state.filter == TaskFilter.Chores) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(
                                if (state.filter ==
                                    TaskFilter.Chores
                                ) {
                                    Res.string.tasks_new_chore
                                } else {
                                    Res.string.tasks_new
                                },
                            ),
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xxl * 3),
        ) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = Spacing.sm)) {
                    listOf(
                        TaskFilter.Mine to Res.string.tasks_filter_mine,
                        TaskFilter.All to Res.string.tasks_filter_all,
                        TaskFilter.Chores to Res.string.tasks_filter_chores,
                    ).forEach { (f, label) ->
                        FilterChip(selected = state.filter == f, onClick = { onFilter(f) }, label = { Text(stringResource(label)) })
                    }
                }
            }
            if (state.approvals.isNotEmpty() || (state.canEdit && state.hasChildren)) {
                item(key = "kids") { KidsCard(state, onApprove, onReject, onAllowance) }
            }
            if (state.canEdit) item(key = "add") { QuickAddField(onQuickAdd) }
            if (state.isEmpty) item(key = "empty") { EmptyState(stringResource(Res.string.tasks_empty)) }
            section("overdue", Res.string.today_overdue, state.overdue, state, onDone, onEdit)
            section("today", Res.string.today, state.todayItems, state, onDone, onEdit)
            section("upcoming", Res.string.tasks_upcoming, state.upcoming, state, onDone, onEdit)
            section("undated", Res.string.tasks_undated, state.undated, state, onDone, onEdit)
            section("done", Res.string.tasks_done, state.done, state, onDone, onEdit)
        }
    }
}

private fun LazyListScope.section(
    key: String,
    title: StringResource,
    items: List<TaskOccurrence>,
    state: TasksUiState,
    onDone: (TaskOccurrence, Boolean) -> Unit,
    onEdit: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "h-$key") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = if (key == "overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xxs).semantics { heading() },
        )
    }
    items(items, key = { "$key-${it.task.id}-${it.key}" }) { o -> TaskRow(o, state, onDone, onEdit) }
}

@Composable
private fun TaskRow(o: TaskOccurrence, state: TasksUiState, onDone: (TaskOccurrence, Boolean) -> Unit, onEdit: (String) -> Unit) {
    val assignee = o.assigneeId?.let { state.people[it] }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckRow(
            text = o.task.title,
            checked = o.done,
            onCheckedChange = { onDone(o, it) },
            supporting = taskDetails(o, state),
            leading = assignee?.let { { PersonAvatar(it, size = 24.dp) } },
            modifier = Modifier.weight(1f),
        )
        if (state.canEdit) {
            IconButton(onClick = { onEdit(o.task.id) }) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(Res.string.task_edit_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "Johan · fre 9 okt 18:00 · Turas om: Sara och Johan · 2/4" */
@Composable
private fun taskDetails(o: TaskOccurrence, state: TasksUiState): String? {
    val t = o.task
    val today = state.today
    val parts = buildList {
        // The avatar already shows who; only say it in words when nobody is assigned.
        if (o.assigneeId == null || state.people[o.assigneeId] == null) add(stringResource(Res.string.tasks_anyone))
        o.date?.let { d ->
            val day = if (today != null && d >= today && d <= today.plusDays(1)) relativeDay(d, today) else formatDateShort(d)
            add(listOfNotNull(day, t.dueTime?.let { formatTime(it) }).joinToString(" "))
        }
        t.rotation?.let { r ->
            val names = r.memberIds.mapNotNull { state.people[it]?.name }
            if (names.size > 1) add(stringResource(Res.string.task_rotating, joinNames(names)))
            // Who is next, so nobody has to remember whose turn it is.
            val nextDate = o.date?.plusDays(r.everyWeeks * DAYS_PER_WEEK)
            val next = nextDate?.let { TaskScheduler.assigneeFor(t, it) }?.let { state.people[it]?.name }
            if (next != null && next != state.people[o.assigneeId]?.name) add(stringResource(Res.string.task_next_turn, next))
        }
        if (t.checklist.isNotEmpty()) add("${t.checklist.count { it.done }}/${t.checklist.size}")
        if (t.kind == TaskKind.Chore && t.rotation == null && t.rrule == null) add(stringResource(Res.string.task_kind_chore))
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun QuickAddField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(Res.string.tasks_add_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        leadingIcon = { Icon(PussletIcons.Task, contentDescription = null) },
        trailingIcon = if (text.isNotBlank()) {
            { IconButton(onClick = submit) { Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.action_add)) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DAYS_PER_WEEK = 7

/** The children's ticks waiting for a parent, and the way to veckopeng. */
@Composable
private fun KidsCard(
    state: TasksUiState,
    onApprove: (PendingApproval) -> Unit,
    onReject: (PendingApproval) -> Unit,
    onAllowance: () -> Unit,
) {
    SectionCard(
        stringResource(Res.string.kids_title),
        modifier = Modifier.padding(vertical = Spacing.sm),
        action = { SmallAction(stringResource(Res.string.allowance_title)) { onAllowance() } },
    ) {
        if (state.approvals.isEmpty()) {
            Text(stringResource(Res.string.kids_nothing_to_approve), style = MaterialTheme.typography.bodyMedium)
        }
        state.approvals.forEach { p ->
            val who = state.people[p.childId]?.name.orEmpty()
            Column {
                Text(stringResource(Res.string.kids_done_line, who, p.task.title), style = MaterialTheme.typography.bodyLarge)
                if (p.stars > 0) Text(stringResource(Res.string.stars_count, p.stars), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { onApprove(p) }) { Text(stringResource(Res.string.kids_approve)) }
                    TextButton(onClick = { onReject(p) }) { Text(stringResource(Res.string.kids_reject)) }
                }
            }
        }
    }
}

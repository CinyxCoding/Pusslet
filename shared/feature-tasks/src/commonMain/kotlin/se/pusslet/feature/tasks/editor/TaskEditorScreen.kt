package se.pusslet.feature.tasks.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.tasks.TaskRepeat
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.OutlinedValueField
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.ReorderButtons
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SentenceRow
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.TimePickerSheet
import se.pusslet.ui.components.UnsavedChangesBackHandler
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.action_cancel
import se.pusslet.ui.resources.action_delete
import se.pusslet.ui.resources.action_discard
import se.pusslet.ui.resources.action_save
import se.pusslet.ui.resources.action_swap_week
import se.pusslet.ui.resources.discard_changes_title
import se.pusslet.ui.resources.event_note
import se.pusslet.ui.resources.event_title_required
import se.pusslet.ui.resources.field_assignee
import se.pusslet.ui.resources.field_due
import se.pusslet.ui.resources.field_repeat
import se.pusslet.ui.resources.field_start
import se.pusslet.ui.resources.field_time
import se.pusslet.ui.resources.field_title
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.repeat_biweekly
import se.pusslet.ui.resources.repeat_biweekly_on
import se.pusslet.ui.resources.repeat_daily
import se.pusslet.ui.resources.repeat_every_n_weeks
import se.pusslet.ui.resources.repeat_monthly
import se.pusslet.ui.resources.repeat_none
import se.pusslet.ui.resources.repeat_weekly
import se.pusslet.ui.resources.repeat_weekly_on
import se.pusslet.ui.resources.tasks_assignee_none
import se.pusslet.ui.resources.tasks_checklist
import se.pusslet.ui.resources.tasks_checklist_add
import se.pusslet.ui.resources.tasks_checklist_remove
import se.pusslet.ui.resources.tasks_clear_date
import se.pusslet.ui.resources.tasks_clear_time
import se.pusslet.ui.resources.tasks_edit
import se.pusslet.ui.resources.tasks_kind_chore
import se.pusslet.ui.resources.tasks_kind_task
import se.pusslet.ui.resources.tasks_new
import se.pusslet.ui.resources.tasks_no_date
import se.pusslet.ui.resources.tasks_no_time
import se.pusslet.ui.resources.tasks_rotation_every
import se.pusslet.ui.resources.tasks_rotation_members
import se.pusslet.ui.resources.tasks_rotation_on
import se.pusslet.ui.resources.tasks_shared_helpers
import se.pusslet.ui.resources.tasks_turn_next_week
import se.pusslet.ui.resources.tasks_turn_then
import se.pusslet.ui.resources.tasks_turn_this_week
import se.pusslet.ui.resources.tasks_turn_week
import se.pusslet.ui.resources.tasks_whose_turn
import se.pusslet.ui.theme.Spacing

@Composable
fun TaskEditorRoute(taskId: String?, onDone: () -> Unit, viewModel: TaskEditorViewModel = koinViewModel()) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    var confirmDiscard by remember { mutableStateOf(false) }
    UnsavedChangesBackHandler(enabled = state.hasChanges && !confirmDiscard) { confirmDiscard = true }
    TaskEditorScreen(
        state = state,
        preview = viewModel::taskOf,
        actions = TaskEditorActions(
            onBack = { if (state.hasChanges) confirmDiscard = true else onDone() },
            onTitle = viewModel::setTitle,
            onKind = viewModel::setKind,
            onAssignee = viewModel::setAssignee,
            onDue = viewModel::setDue,
            onDueTime = viewModel::setDueTime,
            onRepeat = viewModel::setRepeat,
            onRotationOn = viewModel::setRotationOn,
            onToggleMember = viewModel::toggleRotationMember,
            onMoveMember = viewModel::moveRotationMember,
            onEveryWeeks = viewModel::setEveryWeeks,
            onSwapWeek = viewModel::swapWeek,
            onAddItem = viewModel::addChecklistItem,
            onRemoveItem = viewModel::removeChecklistItem,
            onMoveItem = viewModel::moveChecklistItem,
            onToggleItem = viewModel::toggleChecklistItem,
            onEdit = viewModel::edit,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        ),
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(Res.string.discard_changes_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text(stringResource(Res.string.action_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** What the editor's controls do. The defaults do nothing, for previews and screenshot tests of the screen alone. */
data class TaskEditorActions(
    val onBack: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onKind: (TaskKind) -> Unit = {},
    val onAssignee: (String?) -> Unit = {},
    val onDue: (LocalDate?) -> Unit = {},
    val onDueTime: (LocalTime?) -> Unit = {},
    val onRepeat: (TaskRepeat) -> Unit = {},
    val onRotationOn: (Boolean) -> Unit = {},
    val onToggleMember: (String) -> Unit = {},
    val onMoveMember: (index: Int, delta: Int) -> Unit = { _, _ -> },
    val onEveryWeeks: (Int) -> Unit = {},
    val onSwapWeek: () -> Unit = {},
    val onAddItem: (String) -> Unit = {},
    val onRemoveItem: (String) -> Unit = {},
    val onMoveItem: (index: Int, delta: Int) -> Unit = { _, _ -> },
    val onToggleItem: (String) -> Unit = {},
    val onEdit: ((TaskDraft) -> TaskDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * The task editor: Titel → Uppgift | Syssla → who (or "Turas om" with "Vems tur" for a chore) → when and how often → the
 * checklist, a note and "Visa för hjälpare". "Spara" is fixed at the bottom; deleting is rare and sits in ⋮ (with "Ångra"
 * on the list instead of "Är du säker?").
 */
@Composable
fun TaskEditorScreen(state: TaskEditorState, preview: (TaskDraft) -> FamilyTask, actions: TaskEditorActions) {
    Scaffold(
        topBar = {
            PussletTopBar(
                title = stringResource(if (state.isNew) Res.string.tasks_new else Res.string.tasks_edit),
                onBack = actions.onBack,
                actions = { if (!state.isNew) DeleteMenu(actions.onDelete) },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = actions.onSave,
                    enabled = !state.saving && state.draft != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                        .heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.action_save)) }
            }
        },
    ) { padding ->
        val d = state.draft
        if (d == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val titleMissing = TaskEditorError.TitleRequired in state.errors
            OutlinedTextField(
                value = d.title,
                onValueChange = actions.onTitle,
                label = { Text(stringResource(Res.string.field_title)) },
                isError = titleMissing,
                supportingText = if (titleMissing) ({ Text(stringResource(Res.string.event_title_required)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            KindSwitch(d.kind, actions.onKind)
            WhoSection(state, d, preview, actions)
            WhenSection(d, state.today, actions)
            ChecklistSection(d, actions)
            OutlinedTextField(
                value = d.note,
                onValueChange = { v -> actions.onEdit { it.copy(note = v) } },
                label = { Text(stringResource(Res.string.event_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(Res.string.tasks_shared_helpers), d.sharedWithCaregivers, { v ->
                actions.onEdit { it.copy(sharedWithCaregivers = v) }
            })
        }
    }
}

@Composable
private fun KindSwitch(kind: TaskKind, onKind: (TaskKind) -> Unit) {
    val kinds = listOf(TaskKind.Task to Res.string.tasks_kind_task, TaskKind.Chore to Res.string.tasks_kind_chore)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { i, (k, label) ->
            SegmentedButton(
                selected = kind == k,
                onClick = { onKind(k) },
                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                colors = SelectionColors.segmentedButton(),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

/** "Ansvarig" (or "Ingen"); a chore can instead take turns. */
@Composable
private fun WhoSection(state: TaskEditorState, d: TaskDraft, preview: (TaskDraft) -> FamilyTask, actions: TaskEditorActions) {
    if (d.kind == TaskKind.Chore) SwitchRow(stringResource(Res.string.tasks_rotation_on), d.rotationOn, actions.onRotationOn)
    if (d.kind == TaskKind.Chore && d.rotationOn) {
        RotationSection(state, d, preview, actions)
    } else {
        PersonPicker(stringResource(Res.string.field_assignee), state.adults, d.assigneeId, actions.onAssignee)
    }
}

/** Who takes turns, in what order and how often, then "Vems tur" with "Byt vecka". */
@Composable
private fun RotationSection(state: TaskEditorState, d: TaskDraft, preview: (TaskDraft) -> FamilyTask, actions: TaskEditorActions) {
    val byId = state.adults.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldHeading(stringResource(Res.string.tasks_rotation_members))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            state.adults.forEach { p ->
                FilterChip(
                    selected = p.id in d.rotationMembers,
                    onClick = { actions.onToggleMember(p.id) },
                    label = { Text(p.name) },
                    leadingIcon = { PersonAvatar(p, size = 20.dp) },
                    colors = SelectionColors.filterChip(),
                )
            }
        }
        if (d.rotationMembers.size > 1) {
            d.rotationMembers.forEachIndexed { i, id ->
                val name = byId[id]?.name ?: return@forEachIndexed
                Row(Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    ReorderButtons(
                        name = name,
                        canMoveUp = i > 0,
                        canMoveDown = i < d.rotationMembers.lastIndex,
                        onMoveUp = { actions.onMoveMember(i, -1) },
                        onMoveDown = { actions.onMoveMember(i, 1) },
                    )
                }
            }
        }
    }
    EveryWeeksField(d.everyWeeks, actions.onEveryWeeks)
    val week = state.thisWeek
    if (week != null && d.rotationMembers.isNotEmpty()) WhoseTurn(state, preview(d), week, byId, actions.onSwapWeek)
}

/** "Den här veckan: Sara", "Nästa vecka: Johan", "Byt vecka", then "Sedan vecka 45: Sara, vecka 46: Johan". */
@Composable
private fun WhoseTurn(state: TaskEditorState, task: FamilyTask, week: IsoWeek, byId: Map<String, FamilyPerson>, onSwap: () -> Unit) {
    val turns = TaskScheduler.turns(task, week, TURN_PREVIEW_WEEKS)

    fun name(id: String?) = id?.let { byId[it]?.name }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldHeading(stringResource(Res.string.tasks_whose_turn))
        val (thisWeek, nextWeek) = turns[0].second to turns[1].second
        name(thisWeek)?.let {
            SentenceRow(stringResource(Res.string.tasks_turn_this_week, it), emphasized = thisWeek == state.me)
        }
        name(nextWeek)?.let {
            SentenceRow(stringResource(Res.string.tasks_turn_next_week, it), emphasized = nextWeek == state.me)
        }
        TextButton(onClick = onSwap, enabled = thisWeek != null && nextWeek != null && thisWeek != nextWeek) {
            Icon(PussletIcons.Swap, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
            Text(stringResource(Res.string.action_swap_week))
        }
        val later = turns.drop(2).mapNotNull { (w, id) -> name(id)?.let { stringResource(Res.string.tasks_turn_week, w.week, it) } }
        if (later.isNotEmpty()) {
            Text(
                stringResource(Res.string.tasks_turn_then, later.joinToString(stringResource(Res.string.list_separator))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EveryWeeksField(weeks: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedValueField(stringResource(Res.string.tasks_rotation_every), everyLabel(weeks), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in 1..TaskEditorViewModel.MAX_EVERY_WEEKS) {
                DropdownMenuItem(text = { Text(everyLabel(n)) }, onClick = {
                    onChange(n)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun everyLabel(weeks: Int): String = when (weeks) {
    1 -> stringResource(Res.string.repeat_weekly)
    2 -> stringResource(Res.string.repeat_biweekly)
    else -> stringResource(Res.string.repeat_every_n_weeks, weeks)
}

/** "Klart senast" (or "Börjar" for a repeating task) with an optional time, then "Upprepas". */
@Composable
private fun WhenSection(d: TaskDraft, today: LocalDate?, actions: TaskEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            val dateLabel = stringResource(if (d.repeat == TaskRepeat.None) Res.string.field_due else Res.string.field_start)
            DateField(dateLabel, d.due, today, actions.onDue, Modifier.weight(1f))
            if (d.due != null) TimeField(d.dueTime, actions.onDueTime, Modifier.width(TimeFieldWidth))
        }
        RepeatField(d, actions.onRepeat)
    }
}

@Composable
private fun DateField(label: String, date: LocalDate?, today: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedValueField(
            label,
            date?.let { formatDateLong(it) },
            onClick = { open = true },
            placeholder = stringResource(Res.string.tasks_no_date),
            modifier = Modifier.weight(1f),
        )
        if (date != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.tasks_clear_date))
            }
        }
    }
    val start = date ?: today
    if (open && start != null) DatePickerSheet(start, onDismiss = { open = false }, onPicked = onChange)
}

@Composable
private fun TimeField(time: LocalTime?, onChange: (LocalTime?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedValueField(
            stringResource(Res.string.field_time),
            time?.let(::formatTime),
            onClick = { open = true },
            placeholder = stringResource(Res.string.tasks_no_time),
            modifier = Modifier.weight(1f),
        )
        if (time != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.tasks_clear_time))
            }
        }
    }
    if (open) TimePickerSheet(time ?: DefaultTime, onDismiss = { open = false }, onPicked = onChange)
}

@Composable
private fun RepeatField(d: TaskDraft, onRepeat: (TaskRepeat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val day = d.due?.let { weekdayName(it.dayOfWeek) }
    val labels: List<Pair<TaskRepeat, String>> = buildList {
        add(TaskRepeat.None to stringResource(Res.string.repeat_none))
        add(TaskRepeat.Daily to stringResource(Res.string.repeat_daily))
        add(TaskRepeat.Weekly to (day?.let { stringResource(Res.string.repeat_weekly_on, it) } ?: stringResource(Res.string.repeat_weekly)))
        add(
            TaskRepeat.Biweekly to
                (day?.let { stringResource(Res.string.repeat_biweekly_on, it) } ?: stringResource(Res.string.repeat_biweekly)),
        )
        add(TaskRepeat.Monthly to stringResource(Res.string.repeat_monthly))
        if (d.customRule != null) add(TaskRepeat.Custom to describe(d.customRule))
    }
    val current = labels.firstOrNull { it.first == d.repeat }?.second ?: describe(d.customRule)
    Box {
        OutlinedValueField(stringResource(Res.string.field_repeat), current, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (option, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onRepeat(option)
                    open = false
                })
            }
        }
    }
}

/** "Checklista": tick, move with buttons (works with TalkBack), remove, and a field to add a line. */
@Composable
private fun ChecklistSection(d: TaskDraft, actions: TaskEditorActions) {
    var text by rememberSaveable { mutableStateOf("") }

    fun add() {
        actions.onAddItem(text)
        text = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldHeading(stringResource(Res.string.tasks_checklist))
        d.checklist.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Spacing.touchTarget)
                        .toggleable(value = item.done, role = Role.Checkbox) { actions.onToggleItem(item.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = item.done, onCheckedChange = null)
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
                ReorderButtons(
                    name = item.text,
                    canMoveUp = i > 0,
                    canMoveDown = i < d.checklist.lastIndex,
                    onMoveUp = { actions.onMoveItem(i, -1) },
                    onMoveDown = { actions.onMoveItem(i, 1) },
                )
                val remove = stringResource(Res.string.tasks_checklist_remove, item.text)
                IconButton(onClick = { actions.onRemoveItem(item.id) }, modifier = Modifier.semantics { contentDescription = remove }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.tasks_checklist_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { TextButton(onClick = ::add, enabled = text.isNotBlank()) { Text(stringResource(Res.string.action_add)) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Chooses who is responsible; "Ingen" is the placeholder and the first choice. */
@Composable
private fun PersonPicker(label: String, people: List<FamilyPerson>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val nobody = stringResource(Res.string.tasks_assignee_none)
    Box {
        OutlinedValueField(label, people.firstOrNull { it.id == selected }?.name, onClick = { open = true }, placeholder = nobody)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(nobody) }, onClick = {
                onPick(null)
                open = false
            })
            people.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, leadingIcon = { PersonAvatar(p, size = 24.dp) }, onClick = {
                    onPick(p.id)
                    open = false
                })
            }
        }
    }
}

/** ⋮ with "Ta bort" (rare and destructive, so not a button on the form; the list offers "Ångra"). */
@Composable
private fun DeleteMenu(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.action_delete))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.action_delete)) }, onClick = {
                open = false
                onDelete()
            })
        }
    }
}

@Composable
private fun FieldHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

private val TimeFieldWidth = 160.dp
private val DefaultTime = LocalTime(9, 0)
private const val TURN_PREVIEW_WEEKS = 4

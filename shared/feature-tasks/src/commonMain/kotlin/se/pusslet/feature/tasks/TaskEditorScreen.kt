package se.pusslet.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.RepeatOption
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun TaskEditorRoute(
    taskId: String?,
    chore: Boolean,
    onDone: () -> Unit,
    /** Pictures kept with the note on this phone (filled in by the app shell). */
    attachments: @Composable () -> Unit = {},
    viewModel: TaskEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.load(taskId, chore) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    TaskEditorScreen(
        state = state,
        onBack = onDone,
        onEdit = viewModel::edit,
        onToggleRotation = viewModel::toggleRotation,
        onAddChecklist = viewModel::addChecklistItem,
        onRemoveChecklist = viewModel::removeChecklistItem,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        attachments = attachments,
    )
}

@Composable
fun TaskEditorScreen(
    state: TaskEditorState,
    onBack: () -> Unit,
    onEdit: ((TaskDraft) -> TaskDraft) -> Unit,
    onToggleRotation: (String) -> Unit,
    onAddChecklist: (String) -> Unit,
    onRemoveChecklist: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    attachments: @Composable () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            PussletTopBar(
                stringResource(if (state.isNew) Res.string.tasks_new else Res.string.task_edit_title),
                onBack = onBack,
                actions = { TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(Res.string.action_save)) } },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val d = state.draft
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = d.title,
                onValueChange = { v -> onEdit { it.copy(title = v) } },
                label = { Text(stringResource(Res.string.field_title)) },
                isError = TaskError.TitleRequired in state.errors,
                supportingText = if (TaskError.TitleRequired in state.errors) {
                    { Text(stringResource(Res.string.task_title_required)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            state.linkedEventTitle?.let {
                Text(stringResource(Res.string.task_linked_event, it), style = MaterialTheme.typography.bodyMedium)
            }
            KindRow(d.kind) { k -> onEdit { it.copy(kind = k) } }
            SectionCard(stringResource(Res.string.task_category)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TaskCategory.entries.forEach { c ->
                        FilterChip(selected = d.category == c, onClick = {
                            onEdit { it.copy(category = c) }
                        }, label = { Text(stringResource(categoryLabel(c))) })
                    }
                }
            }
            WhoSection(state, onEdit, onToggleRotation)
            WhenSection(d, state.today, onEdit)
            ChecklistSection(d, onAddChecklist, onRemoveChecklist)
            OutlinedTextField(
                value = d.note,
                onValueChange = { v -> onEdit { it.copy(note = v) } },
                label = { Text(stringResource(Res.string.event_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            attachments()
            SwitchRow(stringResource(Res.string.event_shared_caregivers), d.sharedWithCaregivers, { v ->
                onEdit { it.copy(sharedWithCaregivers = v) }
            })
            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) {
                Text(stringResource(Res.string.action_save))
            }
            if (!state.isNew) {
                OutlinedButton(onClick = {
                    confirmDelete = true
                }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
                    Text(stringResource(Res.string.action_delete))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.task_delete_confirm)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(Res.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun KindRow(kind: TaskKind, onPick: (TaskKind) -> Unit) {
    val options = listOf(TaskKind.Task to Res.string.quickadd_kind_task, TaskKind.Chore to Res.string.task_kind_chore)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (k, label) ->
            SegmentedButton(
                selected = kind == k,
                onClick = { onPick(k) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun WhoSection(state: TaskEditorState, onEdit: ((TaskDraft) -> TaskDraft) -> Unit, onToggleRotation: (String) -> Unit) {
    val d = state.draft
    SectionCard(stringResource(Res.string.task_assignee)) {
        if (d.kind == TaskKind.Chore) {
            SwitchRow(
                stringResource(Res.string.task_rotation),
                d.rotation.isNotEmpty(),
                // Parents take turns by default; a helper can be added by tapping.
                { on ->
                    onEdit {
                        it.copy(
                            rotation = if (on) {
                                state.adults.filter { p ->
                                    p.role != Role.Caregiver
                                }.map { p -> p.id }
                            } else {
                                emptyList()
                            },
                        )
                    }
                },
                supporting = stringResource(Res.string.task_rotation_body),
            )
        }
        if (d.kind == TaskKind.Chore && d.rotation.isNotEmpty()) {
            PersonChips(state.adults, d.rotation.toSet(), onToggleRotation)
            val order = d.rotation.mapNotNull { id -> state.adults.firstOrNull { it.id == id }?.name }
            Text(order.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("  "), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(d.rotationWeeks == 1, {
                    onEdit { it.copy(rotationWeeks = 1) }
                }, label = { Text(stringResource(Res.string.task_rotation_weekly)) })
                FilterChip(d.rotationWeeks == 2, {
                    onEdit { it.copy(rotationWeeks = 2) }
                }, label = { Text(stringResource(Res.string.task_rotation_biweekly)) })
            }
        } else {
            PersonChips(
                state.adults,
                setOfNotNull(d.assigneeId),
                { id -> onEdit { it.copy(assigneeId = if (it.assigneeId == id) null else id) } },
                allLabel = stringResource(Res.string.tasks_anyone),
                onAll = { onEdit { it.copy(assigneeId = null) } },
            )
        }
    }
}

@Composable
private fun WhenSection(d: TaskDraft, today: kotlinx.datetime.LocalDate?, onEdit: ((TaskDraft) -> TaskDraft) -> Unit) {
    SectionCard(stringResource(Res.string.field_due)) {
        val date = d.due
        if (date == null) {
            TextButton(onClick = { onEdit { it.copy(due = today) } }) {
                Text(stringResource(Res.string.task_set_date))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DateField(stringResource(Res.string.field_date), date, { v -> onEdit { it.copy(due = v) } }, Modifier.weight(1f))
                val time = d.dueTime
                if (time != null) {
                    TimeField(stringResource(Res.string.field_time), time, { v -> onEdit { it.copy(dueTime = v) } }, Modifier.weight(1f))
                }
                IconButton(onClick = { onEdit { it.copy(due = null, dueTime = null, repeat = RepeatOption.None) } }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.task_clear_date))
                }
            }
            if (d.dueTime == null) {
                TextButton(onClick = {
                    onEdit { it.copy(dueTime = LocalTime(DEFAULT_HOUR, 0)) }
                }) { Text(stringResource(Res.string.task_add_time)) }
            }
        }
        RepeatPicker(d, onEdit)
    }
}

@Composable
private fun RepeatPicker(d: TaskDraft, onEdit: ((TaskDraft) -> TaskDraft) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val day = d.due?.let { weekdayName(it.dayOfWeek) }
    val labels: List<Pair<RepeatOption, String>> = buildList {
        add(RepeatOption.None to stringResource(Res.string.repeat_none))
        add(RepeatOption.Daily to stringResource(Res.string.repeat_daily))
        add(RepeatOption.Weekdays to stringResource(Res.string.repeat_weekdays))
        add(
            RepeatOption.Weekly to
                if (day != null) stringResource(Res.string.repeat_weekly_on, day) else stringResource(Res.string.repeat_weekly),
        )
        add(
            RepeatOption.Biweekly to
                if (day != null) stringResource(Res.string.repeat_biweekly_on, day) else stringResource(Res.string.repeat_biweekly),
        )
        add(RepeatOption.Monthly to stringResource(Res.string.repeat_monthly))
        d.customRule?.let { add(RepeatOption.Custom to describe(it)) }
    }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
            Text(labels.firstOrNull { it.first == d.repeat }?.second ?: stringResource(Res.string.repeat_none))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (option, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onEdit { it.copy(repeat = option) }
                })
            }
        }
    }
}

@Composable
private fun ChecklistSection(d: TaskDraft, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SectionCard(stringResource(Res.string.task_checklist)) {
        d.checklist.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(item.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.task_checklist_remove, item.text))
                }
            }
        }
        val submit = {
            onAdd(text)
            text = ""
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(Res.string.task_checklist_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, stringResource(Res.string.action_add)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun categoryLabel(c: TaskCategory): StringResource = when (c) {
    TaskCategory.General -> Res.string.category_general
    TaskCategory.Household -> Res.string.category_household
    TaskCategory.Kids -> Res.string.category_kids
    TaskCategory.Errand -> Res.string.category_errand
    TaskCategory.Gift -> Res.string.category_gift
    TaskCategory.Homework -> Res.string.category_homework
    TaskCategory.School -> Res.string.category_school
}

private const val DEFAULT_HOUR = 18

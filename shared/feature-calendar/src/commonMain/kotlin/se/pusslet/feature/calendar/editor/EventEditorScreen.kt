package se.pusslet.feature.calendar.editor

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.recurrence.RepeatOption
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.components.ValueField
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun EventEditorRoute(
    eventId: String?,
    date: LocalDate?,
    occurrenceDate: LocalDate?,
    onDone: () -> Unit,
    viewModel: EventEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(eventId, date, occurrenceDate) { viewModel.load(eventId, date, occurrenceDate) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    EventEditorScreen(
        state = state,
        onBack = onDone,
        onEdit = viewModel::edit,
        onStartDate = viewModel::setStartDate,
        onStartTime = viewModel::setStartTime,
        onTogglePerson = viewModel::togglePerson,
        onToggleTag = viewModel::toggleTag,
        onAddPacking = viewModel::addPacking,
        onRemovePacking = viewModel::removePacking,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

@Composable
fun EventEditorScreen(
    state: EventEditorState,
    onBack: () -> Unit,
    onEdit: ((EventDraft) -> EventDraft) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onStartTime: (LocalTime) -> Unit,
    onTogglePerson: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onAddPacking: (String) -> Unit,
    onRemovePacking: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            PussletTopBar(
                title = stringResource(if (state.isNew) Res.string.calendar_new_event else Res.string.calendar_edit_event),
                onBack = onBack,
                actions = { TextButton(onClick = onSave, enabled = !state.saving) { Text(stringResource(Res.string.action_save)) } },
            )
        },
    ) { padding ->
        val d = state.draft
        if (d == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.singleOccurrence) {
                Banner(
                    stringResource(Res.string.event_editing_occurrence, formatDateLong(d.startDate)),
                    kind = BannerKind.Info,
                )
            }
            OutlinedTextField(
                value = d.title,
                onValueChange = { v -> onEdit { it.copy(title = v) } },
                label = { Text(stringResource(Res.string.field_title)) },
                isError = EditorError.TitleRequired in state.errors,
                supportingText = if (EditorError.TitleRequired in
                    state.errors
                ) {
                    ({ Text(stringResource(Res.string.event_title_required)) })
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            WhenSection(state, d, onEdit, onStartDate, onStartTime)
            if (state.people.isNotEmpty()) {
                SectionCard(stringResource(Res.string.event_participants)) {
                    PersonChips(state.people, d.participants, onTogglePerson)
                }
            }
            if (!d.allDay && d.participants.any { id -> state.people.any { it.id == id && it.isChild } }) {
                HandoffSection(state.adults, d, onEdit)
            }
            OutlinedTextField(
                value = d.location,
                onValueChange = { v -> onEdit { it.copy(location = v) } },
                label = { Text(stringResource(Res.string.event_location)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard(stringResource(Res.string.event_tags)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    EventTags.all.forEach { tag ->
                        val label = Labels.tag(tag)?.let { stringResource(it) } ?: tag
                        FilterChip(selected = tag in d.tags, onClick = { onToggleTag(tag) }, label = { Text(label) })
                    }
                }
            }
            PackingSection(d.packing, onAddPacking, onRemovePacking)
            ReminderField(d.reminder) { r -> onEdit { it.copy(reminder = r) } }
            OutlinedTextField(
                value = d.note,
                onValueChange = { v -> onEdit { it.copy(note = v) } },
                label = { Text(stringResource(Res.string.event_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
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
        val recurring = state.draft?.repeat != RepeatOption.None || state.singleOccurrence
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.event_delete_confirm)) },
            confirmButton = {
                if (recurring) {
                    Column {
                        TextButton(onClick = { onDelete(true) }) { Text(stringResource(Res.string.event_delete_occurrence)) }
                        if (!state.singleOccurrence) {
                            TextButton(onClick = {
                                onDelete(false)
                            }) { Text(stringResource(Res.string.event_delete_series)) }
                        }
                    }
                } else {
                    TextButton(onClick = { onDelete(false) }) { Text(stringResource(Res.string.action_delete)) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun WhenSection(
    state: EventEditorState,
    d: EventDraft,
    onEdit: ((EventDraft) -> EventDraft) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onStartTime: (LocalTime) -> Unit,
) {
    SectionCard(null) {
        SwitchRow(stringResource(Res.string.event_all_day), d.allDay, { v -> onEdit { it.copy(allDay = v) } })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DateField(stringResource(Res.string.field_start), d.startDate, onStartDate, Modifier.weight(1f))
            if (!d.allDay) TimeField(stringResource(Res.string.field_time), d.startTime, onStartTime, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DateField(stringResource(Res.string.field_end), d.endDate, { v -> onEdit { it.copy(endDate = v) } }, Modifier.weight(1f))
            if (!d.allDay) {
                TimeField(stringResource(Res.string.field_time), d.endTime, { v ->
                    onEdit { it.copy(endTime = v) }
                }, Modifier.weight(1f))
            }
        }
        if (EditorError.EndBeforeStart in state.errors) {
            Text(
                stringResource(Res.string.event_end_before_start),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!state.singleOccurrence) RepeatField(d, onEdit)
    }
}

@Composable
private fun RepeatField(d: EventDraft, onEdit: ((EventDraft) -> EventDraft) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val day = weekdayName(d.startDate.dayOfWeek)
    val labels: List<Pair<RepeatOption, String>> = buildList {
        add(RepeatOption.None to stringResource(Res.string.repeat_none))
        add(RepeatOption.Daily to stringResource(Res.string.repeat_daily))
        add(RepeatOption.Weekdays to stringResource(Res.string.repeat_weekdays))
        add(RepeatOption.Weekly to stringResource(Res.string.repeat_weekly_on, day))
        add(RepeatOption.Biweekly to stringResource(Res.string.repeat_biweekly_on, day))
        add(RepeatOption.Monthly to stringResource(Res.string.repeat_monthly))
        add(RepeatOption.Yearly to stringResource(Res.string.repeat_yearly))
        if (d.customRule != null) add(RepeatOption.Custom to describe(d.customRule))
    }
    val current = labels.firstOrNull { it.first == d.repeat }?.second ?: describe(d.customRule)
    Column {
        ValueField(stringResource(Res.string.field_repeat), current, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (option, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onEdit { it.copy(repeat = option) }
                    open = false
                })
            }
        }
    }
}

@Composable
private fun HandoffSection(adults: List<FamilyPerson>, d: EventDraft, onEdit: ((EventDraft) -> EventDraft) -> Unit) {
    SectionCard(stringResource(Res.string.event_handoffs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonPicker(stringResource(Res.string.event_dropoff), adults, d.dropOffBy, Modifier.weight(1f)) { id ->
                onEdit { it.copy(dropOffBy = id) }
            }
            TimeField(stringResource(Res.string.field_time), d.dropOffTime ?: d.startTime, { t ->
                onEdit { it.copy(dropOffTime = t) }
            }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonPicker(stringResource(Res.string.event_pickup), adults, d.pickUpBy, Modifier.weight(1f)) { id ->
                onEdit { it.copy(pickUpBy = id) }
            }
            TimeField(stringResource(Res.string.field_time), d.pickUpTime ?: d.endTime, { t ->
                onEdit { it.copy(pickUpTime = t) }
            }, Modifier.weight(1f))
        }
    }
}

@Composable
fun PersonPicker(label: String, people: List<FamilyPerson>, selected: String?, modifier: Modifier = Modifier, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = people.firstOrNull { it.id == selected }?.name ?: stringResource(Res.string.event_handoff_none)
    Column(modifier) {
        ValueField(label, name, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.event_handoff_none)) }, onClick = {
                onPick(null)
                open = false
            })
            people.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = {
                    onPick(p.id)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun PackingSection(items: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    SectionCard(stringResource(Res.string.event_packing)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(item) },
                    label = { Text(item) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete)) },
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.event_packing_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onAdd(text)
                text = ""
            }),
            trailingIcon = {
                TextButton(onClick = {
                    onAdd(text)
                    text = ""
                }, enabled = text.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReminderField(minutes: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val platform = se.pusslet.ui.platform.LocalPlatformActions.current
    Column {
        ValueField(stringResource(Res.string.event_reminder), reminderLabel(minutes), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EventEditorViewModel.reminderOptions.forEach { m ->
                DropdownMenuItem(text = { Text(reminderLabel(m)) }, onClick = {
                    onChange(m)
                    open = false
                    // Asked in context: only when someone actually wants a reminder.
                    if (m != null && !platform.hasPermission(se.pusslet.ui.platform.AppPermission.Notifications)) {
                        platform.requestPermission(se.pusslet.ui.platform.AppPermission.Notifications) {}
                    }
                })
            }
        }
    }
}

@Composable
fun reminderLabel(minutes: Int?): String = when {
    minutes == null -> stringResource(Res.string.reminder_none)
    minutes >= DAY_MINUTES -> stringResource(Res.string.reminder_day)
    minutes >= HOUR_MINUTES -> stringResource(Res.string.reminder_hours, minutes / HOUR_MINUTES)
    else -> stringResource(Res.string.reminder_minutes, minutes)
}

private const val HOUR_MINUTES = 60
private const val DAY_MINUTES = 24 * 60

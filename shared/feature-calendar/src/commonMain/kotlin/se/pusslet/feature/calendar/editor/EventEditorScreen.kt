package se.pusslet.feature.calendar.editor

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.quickadd.EventPrefill
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.OutlinedValueField
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.TimePickerSheet
import se.pusslet.ui.format.describe
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun EventEditorRoute(
    eventId: String?,
    date: LocalDate?,
    occurrenceDate: LocalDate?,
    onDone: () -> Unit,
    /** What quick add understood ("Fyll i själv"); only for a new event. */
    prefill: EventPrefill? = null,
    viewModel: EventEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(eventId, date, occurrenceDate) { viewModel.load(eventId, date, occurrenceDate, prefill) }
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

/**
 * The event editor (UX plan, Kalender 7): Titel → Vem gäller det? → När → "Vem lämnar/hämtar Simon?", the rest under
 * "Fler detaljer" and "Spara" fixed at the bottom. Deleting is rare and sits in the ⋮ menu.
 */
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
                actions = { if (!state.isNew) DeleteMenu { confirmDelete = true } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = onSave,
                    enabled = !state.saving && state.draft != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                        .heightIn(min = Spacing.primaryTouchTarget),
                ) {
                    Text(stringResource(Res.string.action_save))
                }
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
                supportingText = if (EditorError.TitleRequired in state.errors) {
                    { Text(stringResource(Res.string.event_title_required)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.people.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FieldHeading(stringResource(Res.string.event_participants))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.people.sortedBy { !it.isChild }.forEach { p ->
                            FilterChip(
                                selected = p.id in d.participants,
                                onClick = { onTogglePerson(p.id) },
                                label = { Text(p.name) },
                                leadingIcon = { PersonAvatar(p, size = 20.dp) },
                                colors = SelectionColors.filterChip(),
                            )
                        }
                    }
                }
            }
            WhenSection(state, d, onEdit, onStartDate, onStartTime)
            val children = state.people.filter { it.isChild && it.id in d.participants }
            if (!d.allDay && children.isNotEmpty()) HandoffSection(state.adults, children, d, onEdit)
            MoreDetails(d, onEdit, onToggleTag, onAddPacking, onRemovePacking)
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

/** ⋮ with "Ta bort" (rare and destructive, so not a button on the form). */
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
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WhenSection(
    state: EventEditorState,
    d: EventDraft,
    onEdit: ((EventDraft) -> EventDraft) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onStartTime: (LocalTime) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SwitchRow(stringResource(Res.string.event_all_day), d.allDay, { v -> onEdit { it.copy(allDay = v) } })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DateValueField(stringResource(Res.string.field_start), d.startDate, onStartDate, Modifier.weight(1f))
            if (!d.allDay) TimeValueField(stringResource(Res.string.field_time), d.startTime, onStartTime, Modifier.width(TimeFieldWidth))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DateValueField(stringResource(Res.string.field_end), d.endDate, { v -> onEdit { it.copy(endDate = v) } }, Modifier.weight(1f))
            if (!d.allDay) {
                TimeValueField(stringResource(Res.string.field_time), d.endTime, { v ->
                    onEdit { it.copy(endTime = v) }
                }, Modifier.width(TimeFieldWidth))
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
private fun DateValueField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedValueField(label, formatDateLong(date), onClick = { open = true }, modifier = modifier)
    if (open) DatePickerSheet(date, onDismiss = { open = false }, onPicked = onChange)
}

@Composable
private fun TimeValueField(label: String, time: LocalTime, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedValueField(label, formatTime(time), onClick = { open = true }, modifier = modifier)
    if (open) TimePickerSheet(time, onDismiss = { open = false }, onPicked = onChange)
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
    Box {
        OutlinedValueField(stringResource(Res.string.field_repeat), current, onClick = { open = true })
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

/** "Vem lämnar Simon?" and "Vem hämtar Simon?", each with its time; "Inte bestämt" until someone is chosen. */
@Composable
private fun HandoffSection(
    adults: List<FamilyPerson>,
    children: List<FamilyPerson>,
    d: EventDraft,
    onEdit: ((EventDraft) -> EventDraft) -> Unit,
) {
    val names = joinNames(children.map { it.name })
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonPicker(stringResource(Res.string.calendar_who_drops_off, names), adults, d.dropOffBy, Modifier.weight(1f)) { id ->
                onEdit { it.copy(dropOffBy = id) }
            }
            TimeValueField(stringResource(Res.string.field_time), d.dropOffTime ?: d.startTime, { t ->
                onEdit { it.copy(dropOffTime = t) }
            }, Modifier.width(TimeFieldWidth))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PersonPicker(stringResource(Res.string.calendar_who_picks_up, names), adults, d.pickUpBy, Modifier.weight(1f)) { id ->
                onEdit { it.copy(pickUpBy = id) }
            }
            TimeValueField(stringResource(Res.string.field_time), d.pickUpTime ?: d.endTime, { t ->
                onEdit { it.copy(pickUpTime = t) }
            }, Modifier.width(TimeFieldWidth))
        }
    }
}

/** Chooses an adult for a hand-off; "Inte bestämt" is the placeholder and the first choice. */
@Composable
fun PersonPicker(label: String, people: List<FamilyPerson>, selected: String?, modifier: Modifier = Modifier, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val nobody = stringResource(Res.string.event_handoff_none)
    Box(modifier) {
        OutlinedValueField(label, people.firstOrNull { it.id == selected }?.name, onClick = { open = true }, placeholder = nobody)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(nobody) }, onClick = {
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

/** "Fler detaljer": place, type, what to bring, reminder, note and who sees it. Open from the start when any of it is set. */
@Composable
private fun MoreDetails(
    d: EventDraft,
    onEdit: ((EventDraft) -> EventDraft) -> Unit,
    onToggleTag: (String) -> Unit,
    onAddPacking: (String) -> Unit,
    onRemovePacking: (String) -> Unit,
) {
    val hasDetails = d.location.isNotBlank() || d.tags.isNotEmpty() || d.packing.isNotEmpty() || d.reminder != null ||
        d.note.isNotBlank() || !d.sharedWithCaregivers
    var open by rememberSaveable { mutableStateOf(hasDetails) }
    val label = stringResource(Res.string.calendar_more_details)
    TextButton(onClick = { open = !open }) {
        Text(label)
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
    if (!open) return
    OutlinedTextField(
        value = d.location,
        onValueChange = { v -> onEdit { it.copy(location = v) } },
        label = { Text(stringResource(Res.string.event_location)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldHeading(stringResource(Res.string.event_tags))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EventTags.all.forEach { tag ->
                val tagLabel = Labels.tag(tag)?.let { stringResource(it) } ?: tag
                FilterChip(
                    selected = tag in d.tags,
                    onClick = { onToggleTag(tag) },
                    label = { Text(tagLabel) },
                    colors = SelectionColors.filterChip(),
                )
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
}

@Composable
private fun PackingSection(items: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldHeading(stringResource(Res.string.event_packing))
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
    val platform = LocalPlatformActions.current
    Box {
        OutlinedValueField(stringResource(Res.string.event_reminder), reminderLabel(minutes), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EventEditorViewModel.reminderOptions.forEach { m ->
                DropdownMenuItem(text = { Text(reminderLabel(m)) }, onClick = {
                    onChange(m)
                    // Asked in context (BRIEF §6): the reminder is kept either way, the phone just stays quiet without it.
                    if (m != null && !platform.hasPermission(AppPermission.Notifications)) {
                        platform.requestPermission(AppPermission.Notifications) {}
                    }
                    open = false
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
private val TimeFieldWidth = 120.dp
private const val DAY_MINUTES = 24 * 60

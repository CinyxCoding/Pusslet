package se.pusslet.feature.calendar.breaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.breaks.Breaks
import se.pusslet.core.breaks.SchoolBreak
import se.pusslet.core.model.BreakCare
import se.pusslet.core.model.BreakDay
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Role
import se.pusslet.core.model.StayDay
import se.pusslet.core.model.StaySchedule
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.BreakRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

/** A child's hours, and what the calendar says they are. */
data class StayRow(val child: FamilyPerson, val schedule: StaySchedule, val fromCalendar: Map<DayOfWeek, StayDay>)

data class BreaksUiState(
    val loading: Boolean = true,
    val canEdit: Boolean = false,
    val today: LocalDate? = null,
    val breaks: List<SchoolBreak> = emptyList(),
    /** Who can be at home: the adults in the family, caregivers too. */
    val adults: List<FamilyPerson> = emptyList(),
    val children: List<FamilyPerson> = emptyList(),
    val stays: List<StayRow> = emptyList(),
)

/** "Lov och vistelsetider" (ADR-051). */
class BreaksViewModel(
    calendar: CalendarRepository,
    family: FamilyRepository,
    private val repo: BreakRepository,
    private val sessions: SessionProvider,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val today = clock.todayIn(zone)

    val state: StateFlow<BreaksUiState> = combine(
        calendar.allOccurrences(today, today.plusDays(YEAR_DAYS), zone),
        repo.plans,
        repo.schedules,
        family.people,
        sessions.session,
    ) { occurrences, plans, schedules, people, s ->
        val children = people.filter { it.isChild }
        val nextTwoWeeks = occurrences.filter { it.start.date < today.plusDays(TWO_WEEKS) }
        BreaksUiState(
            loading = false,
            canEdit = s.role == Role.Parent,
            today = today,
            breaks = Breaks.breaks(occurrences, plans, today),
            adults = people.filter { !it.isChild }.sortedWith(compareBy({ it.id != s.memberId }, { it.role != Role.Parent })),
            children = children,
            stays = children.map { c ->
                StayRow(c, schedules.firstOrNull { it.childId == c.id } ?: StaySchedule(c.id), Breaks.stayFromCalendar(c.id, nextTwoWeeks))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BreaksUiState())

    fun addBreak(title: String, start: LocalDate, end: LocalDate) = write { repo.addBreak(title, start, end, emptyList()) }

    fun setDay(b: SchoolBreak, date: LocalDate, day: BreakDay?) = write { repo.setDay(b, date, day) }

    fun setDeadline(b: SchoolBreak, deadline: LocalDate?) = write { repo.setDeadline(b, deadline) }

    fun setReported(b: SchoolBreak, reported: Boolean) = write { repo.setReported(b, reported) }

    fun saveSchedule(schedule: StaySchedule) = write { repo.saveSchedule(schedule) }

    private fun write(block: suspend () -> Unit) {
        if (!state.value.canEdit) return
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val YEAR_DAYS = 365
        const val TWO_WEEKS = 14
    }
}

/** What the view's controls do; the defaults do nothing, for previews and screenshot tests. */
data class BreaksActions(
    val onBack: () -> Unit = {},
    val onAddBreak: (String, LocalDate, LocalDate) -> Unit = { _, _, _ -> },
    val onDay: (SchoolBreak, LocalDate, BreakDay?) -> Unit = { _, _, _ -> },
    val onDeadline: (SchoolBreak, LocalDate?) -> Unit = { _, _ -> },
    val onReported: (SchoolBreak, Boolean) -> Unit = { _, _ -> },
    val onSchedule: (StaySchedule) -> Unit = {},
    val onShare: (String) -> Unit = {},
)

@Composable
fun BreaksRoute(stays: Boolean = false, onBack: () -> Unit, viewModel: BreaksViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    BreaksScreen(
        state,
        startOnStays = stays,
        actions = BreaksActions(
            onBack = onBack,
            onAddBreak = viewModel::addBreak,
            onDay = viewModel::setDay,
            onDeadline = viewModel::setDeadline,
            onReported = viewModel::setReported,
            onSchedule = viewModel::saveSchedule,
            onShare = { platform.shareText(it) },
        ),
    )
}

@Composable
fun BreaksScreen(state: BreaksUiState, startOnStays: Boolean = false, actions: BreaksActions = BreaksActions()) {
    var stays by rememberSaveable { mutableStateOf(startOnStays) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.breaks_title), onBack = actions.onBack) }) { padding ->
        val today = state.today
        if (state.loading || today == null) return@Scaffold
        if (!state.canEdit) {
            EmptyState(message = stringResource(Res.string.breaks_parents_only), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "switch") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to Res.string.breaks_tab_breaks, true to Res.string.breaks_tab_stays).forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = stays == value,
                            onClick = { stays = value },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (!stays) {
                item(key = "add") { OutlinedButton(onClick = { adding = true }) { Text(stringResource(Res.string.breaks_add)) } }
                if (state.breaks.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(Res.string.breaks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.breaks, key = { it.planId }) { b -> BreakCard(b, state, today, actions) }
            } else {
                if (state.children.isEmpty()) {
                    item(key = "no-children") { Text(stringResource(Res.string.medicine_no_children)) }
                }
                items(state.stays, key = { it.child.id }) { row -> StayCard(row, today, actions) }
            }
        }
    }
    if (adding) {
        AddBreakSheet(state.today ?: return, onDismiss = { adding = false }) { title, start, end ->
            adding = false
            actions.onAddBreak(title, start, end)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakCard(b: SchoolBreak, state: BreaksUiState, today: LocalDate, actions: BreaksActions) {
    val range = if (b.start == b.end) formatDateShort(b.start) else "${formatDateShort(b.start)}–${formatDateShort(b.end)}"
    SectionCard("${b.title} · $range") {
        if (b.days.isEmpty()) Text(stringResource(Res.string.breaks_no_weekdays), style = MaterialTheme.typography.bodySmall)
        b.days.forEach { date ->
            val chosen = b.plan.days[date.toString()]
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "${weekdayName(date.dayOfWeek).replaceFirstChar {
                        it.uppercase()
                    }} ${formatDateShort(date)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    state.adults.forEach { adult ->
                        val selected = chosen?.care == BreakCare.Home && chosen.memberId == adult.id
                        FilterChip(
                            selected = selected,
                            onClick = { actions.onDay(b, date, if (selected) null else BreakDay(BreakCare.Home, adult.id)) },
                            label = { Text(adult.name) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                    val care = chosen?.care == BreakCare.Care
                    FilterChip(
                        selected = care,
                        onClick = { actions.onDay(b, date, if (care) null else BreakDay(BreakCare.Care)) },
                        label = { Text(stringResource(Res.string.breaks_care)) },
                        colors = SelectionColors.filterChip(),
                    )
                    val other = chosen?.care == BreakCare.Other
                    FilterChip(
                        selected = other,
                        onClick = { actions.onDay(b, date, if (other) null else BreakDay(BreakCare.Other)) },
                        label = { Text(stringResource(Res.string.breaks_other)) },
                        colors = SelectionColors.filterChip(),
                    )
                }
            }
        }
        val unplanned = b.unplanned.count { it >= today }
        if (unplanned > 0) {
            Text(
                stringResource(Res.string.breaks_unplanned, unplanned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        DeadlineRow(b, today, actions)
        CheckRow(stringResource(Res.string.breaks_reported), b.plan.reported, { actions.onReported(b, it) })
    }
}

@Composable
private fun DeadlineRow(b: SchoolBreak, today: LocalDate, actions: BreaksActions) {
    val deadline = b.plan.deadline
    if (deadline == null) {
        TextButton(onClick = { actions.onDeadline(b, maxOf(today, b.start.plusDays(-DEFAULT_DEADLINE_DAYS))) }) {
            Text(stringResource(Res.string.breaks_set_deadline))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateField(stringResource(Res.string.breaks_deadline), deadline, { actions.onDeadline(b, it) }, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.onDeadline(b, null) }) { Text(stringResource(Res.string.action_delete)) }
        }
    }
}

@Composable
private fun StayCard(row: StayRow, today: LocalDate, actions: BreaksActions) {
    val schedule = row.schedule
    val shareTitle = stringResource(Res.string.stays_share_title, row.child.name, formatDateShort(schedule.validFrom ?: today))
    val weekdays = WORKDAYS.associateWith { weekdayName(it).replaceFirstChar { c -> c.uppercase() } }
    val total = Breaks.minutesPerWeek(schedule)
    val totalText = stringResource(Res.string.stays_total, total / MINUTES, (total % MINUTES).toString().padStart(2, '0'))
    val offText = stringResource(Res.string.stays_off)
    SectionCard(row.child.name) {
        if (row.fromCalendar.isNotEmpty() && row.fromCalendar != schedule.days) {
            TextButton(onClick = { actions.onSchedule(schedule.copy(days = row.fromCalendar)) }) {
                Text(stringResource(Res.string.stays_from_calendar))
            }
        }
        WORKDAYS.forEach { day ->
            val stay = schedule.days[day]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(weekdays.getValue(day), Modifier.width(DAY_LABEL.dp), style = MaterialTheme.typography.bodyLarge)
                if (stay == null) {
                    TextButton(onClick = {
                        val guess = row.fromCalendar[day] ?: StayDay(LocalTime(8, 0), LocalTime(16, 0))
                        actions.onSchedule(schedule.copy(days = schedule.days + (day to guess)))
                    }) { Text(offText) }
                } else {
                    TimeField(stringResource(Res.string.stays_from), stay.start, { t ->
                        actions.onSchedule(schedule.copy(days = schedule.days + (day to stay.copy(start = t))))
                    }, modifier = Modifier.weight(1f))
                    TimeField(stringResource(Res.string.stays_to), stay.end, { t ->
                        actions.onSchedule(schedule.copy(days = schedule.days + (day to stay.copy(end = t))))
                    }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onSchedule(schedule.copy(days = schedule.days - day)) }) {
                        Text(stringResource(Res.string.stays_clear))
                    }
                }
            }
        }
        DateField(stringResource(Res.string.stays_valid_from), schedule.validFrom ?: today, {
            actions.onSchedule(schedule.copy(validFrom = it))
        })
        Text(totalText, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            val lines = WORKDAYS.map { d ->
                val s = schedule.days[d]
                "${weekdays.getValue(d)} " + if (s == null) offText else "${formatTime(s.start)}–${formatTime(s.end)}"
            }
            actions.onShare((listOf(shareTitle) + lines + totalText).joinToString("\n"))
        }, enabled = schedule.days.isNotEmpty()) { Text(stringResource(Res.string.stays_share)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddBreakSheet(today: LocalDate, onDismiss: () -> Unit, onAdd: (String, LocalDate, LocalDate) -> Unit) {
    val autumn = remember(today) { Breaks.autumnBreak(today) }
    var title by rememberSaveable { mutableStateOf("") }
    var start by remember { mutableStateOf(autumn.first) }
    var end by remember { mutableStateOf(autumn.second) }
    val presets: List<StringResource> = listOf(
        Res.string.breaks_preset_autumn,
        Res.string.breaks_preset_christmas,
        Res.string.breaks_preset_winter,
        Res.string.breaks_preset_easter,
        Res.string.breaks_preset_summer,
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.breaks_add),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                presets.forEach { res ->
                    val name = stringResource(res)
                    FilterChip(
                        selected = title == name,
                        onClick = {
                            title = name
                            if (res == Res.string.breaks_preset_autumn) {
                                start = autumn.first
                                end = autumn.second
                            }
                        },
                        label = { Text(name) },
                        colors = SelectionColors.filterChip(),
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(Res.string.field_title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(stringResource(Res.string.breaks_first_day), start, { picked ->
                start = picked
                if (end < picked) end = picked
            })
            DateField(stringResource(Res.string.breaks_last_day), end, { picked -> end = maxOf(start, picked) })
            Text(stringResource(Res.string.breaks_add_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onAdd(title, start, end) }, enabled = title.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

private val WORKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private const val MINUTES = 60
private const val DAY_LABEL = 72
private const val DEFAULT_DEADLINE_DAYS = 21

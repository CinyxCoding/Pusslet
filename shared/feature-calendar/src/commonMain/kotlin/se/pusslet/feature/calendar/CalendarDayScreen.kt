package se.pusslet.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTimeRange
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

/** One day as its own screen (ADR-033), opened from a day header in the week or month. */
data class CalendarDayUiState(
    val date: LocalDate? = null,
    val today: LocalDate? = null,
    /** Null until loaded. Uses the calendar's person filter and layers. */
    val day: CalendarDay? = null,
    val people: List<FamilyPerson> = emptyList(),
    val role: Role = Role.Parent,
) {
    /** Parents can add events from an empty hour and take hand-offs; caregivers only look (UX plan 1.13). */
    val canEdit: Boolean get() = role == Role.Parent
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDayViewModel(
    private val calendar: CalendarRepository,
    family: FamilyRepository,
    prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val assigner: HandoffAssigner,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val date = MutableStateFlow<LocalDate?>(null)

    private val settings = combine(prefs.observe(Prefs.CalendarPersonFilter), prefs.observe(Prefs.CalendarLayers), ::Pair)

    val state: StateFlow<CalendarDayUiState> = date.filterNotNull().flatMapLatest { d ->
        // A sick child is absent that day (ADR-047); the sick days also give the "Ella sjuk" line.
        val occurrences = combine(calendar.occurrences(d, d, zone), calendar.sickDays, family.children) { occ, sick, kids ->
            SickDays.apply(occ, sick, kids.map { it.id }.toSet()).occurrences to sick
        }
        combine(occurrences, family.everyone, family.custody, settings, sessions.session) {
            occSick,
            people,
            custody,
            s,
            session,
            ->
            val (occ, sick) = occSick
            val (filter, layers) = s
            val filtered = CalendarDays.effectiveFilter(filter, people)
            val day = CalendarDays.build(d, d, occ, people, custody, filtered, layers, session.memberId, sick).firstOrNull()
            CalendarDayUiState(date = d, today = clock.todayIn(zone), day = day, people = people, role = session.role)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarDayUiState(role = sessions.session.value.role))

    /** Shows [day]; later calls keep the day stepped to with ‹ ›. */
    fun load(day: LocalDate) {
        if (date.value == null) date.value = day
    }

    fun step(forward: Boolean) {
        date.value = date.value?.plusDays(if (forward) 1 else -1)
    }

    /** "Jag tar det" on a routine line; parents only. */
    fun take(occurrence: EventOccurrence, kind: HandoffKind) {
        val me = sessions.session.value.memberId ?: return
        if (!state.value.canEdit) return
        viewModelScope.launch { takeHandoff(calendar, assigner, clock, occurrence, kind, me) }
    }
}

@Composable
fun CalendarDayRoute(
    date: LocalDate,
    onBack: () -> Unit,
    onOpenOccurrence: (EventOccurrence) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    viewModel: CalendarDayViewModel = koinViewModel(),
) {
    LaunchedEffect(date) { viewModel.load(date) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CalendarDayScreen(state, onBack, viewModel::step, onOpenOccurrence, onNewEvent, viewModel::take)
}

/**
 * The day: the week number, holidays, routine lines and all-day events on top, then an hour grid from 06 to 22 with the other
 * events at their start hour. No +; a tap on an empty hour opens the editor for the day (parents only).
 */
@Composable
fun CalendarDayScreen(
    state: CalendarDayUiState,
    onBack: () -> Unit,
    onStep: (Boolean) -> Unit,
    onOpen: (EventOccurrence) -> Unit,
    onNew: (LocalDate) -> Unit,
    onTake: (EventOccurrence, HandoffKind) -> Unit,
) {
    val date = state.date
    val today = state.today ?: date
    Scaffold(
        topBar = {
            PussletTopBar(
                title = date?.let { formatDateLong(it, referenceYear = today?.year).replaceFirstChar { c -> c.uppercase() } }.orEmpty(),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { onStep(false) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(Res.string.calendar_previous))
                    }
                    IconButton(onClick = { onStep(true) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(Res.string.calendar_next))
                    }
                },
            )
        },
    ) { padding ->
        val day = state.day
        if (day == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val people = state.people.associateBy { it.id }
        val timed = day.events.filter { !it.allDay }
        val newEvent = stringResource(Res.string.calendar_new_event)
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xl),
        ) {
            item(key = "top") {
                Column(Modifier.padding(bottom = Spacing.sm)) {
                    // The ISO week is always visible (UX plan §7).
                    Text(
                        weekLong(day.date.isoWeek.week),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DayContent(day, people, state.canEdit, onOpen, onTake, timed = false)
                }
            }
            val firstHour = minOf(DAY_START, timed.minOfOrNull { if (it.start.date < day.date) 0 else it.start.hour } ?: DAY_START)
            val lastHour = maxOf(DAY_END, timed.maxOfOrNull { if (it.end.date > day.date) 23 else it.end.hour } ?: DAY_END)
            items((firstHour..lastHour).toList(), key = { "h$it" }) { hour ->
                val starting = timed.filter { (if (it.start.date < day.date) 0 else it.start.hour) == hour }
                val addHere = state.canEdit && starting.isEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        .then(if (addHere) Modifier.clickable(onClickLabel = newEvent) { onNew(day.date) } else Modifier),
                ) {
                    // 24-hour labels without ":00" ("08").
                    Text(
                        hour.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(TimeColumn).padding(top = Spacing.xs),
                    )
                    Column(Modifier.weight(1f)) {
                        starting.forEach { o -> EventBlock(o, people, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBlock(o: EventOccurrence, people: Map<String, FamilyPerson>, onOpen: (EventOccurrence) -> Unit) {
    val first = o.event.participantIds.firstNotNullOfOrNull { people[it] }
    val colors = first?.let { PussletThemeExtras.colors.member(it.color) }
    val minutes = EventExpander.wallClockDuration(o.start, o.end).inWholeMinutes.coerceIn(30, 180)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .heightIn(min = (minutes * 0.8).dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors?.container ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onOpen(o) }
            .padding(Spacing.sm),
    ) {
        Text(
            o.event.title,
            color = colors?.content ?: MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            formatTimeRange(o.start.time, o.end.time),
            color = colors?.content ?: MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private const val DAY_START = 6
private const val DAY_END = 22

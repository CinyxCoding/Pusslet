package se.pusslet.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Household
import se.pusslet.core.time.datesUntil
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.CalendarView
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import kotlin.time.Clock

data class CalendarDay(
    val date: LocalDate,
    val occurrences: List<EventOccurrence>,
    val holidays: List<Holiday>,
    /** Where the children are this day (one entry per custody schedule). */
    val custody: List<Household>,
    val redDay: Boolean,
)

data class CalendarUiState(
    val view: CalendarView = CalendarView.Week,
    val anchor: LocalDate? = null,
    val today: LocalDate? = null,
    val days: List<CalendarDay> = emptyList(),
    val people: List<FamilyPerson> = emptyList(),
    val personFilter: Set<String> = emptySet(),
    val layers: Set<CalendarLayer> = CalendarLayer.entries.toSet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val anchor = MutableStateFlow(clock.todayIn(zone))

    private data class Window(val view: CalendarView, val anchor: LocalDate, val from: LocalDate, val to: LocalDate)

    private val window = combine(prefs.observe(Prefs.CalendarViewMode), anchor) { view, a ->
        val (from, to) = rangeFor(view, a)
        Window(view, a, from, to)
    }

    private val occurrences = window.flatMapLatest { w -> calendar.occurrences(w.from, w.to, zone) }

    private val filters = combine(prefs.observe(Prefs.CalendarPersonFilter), prefs.observe(Prefs.CalendarLayers)) { f, l -> f to l }

    val state: StateFlow<CalendarUiState> = combine(window, occurrences, family.everyone, family.custody, filters) {
        w,
        occ,
        people,
        custody,
        (filter, layers),
        ->
        val visible = if (filter.isEmpty()) occ else occ.filter { o -> o.event.participantIds.any { it in filter } }
        val days = w.from.datesUntil(w.to).map { d ->
            CalendarDay(
                date = d,
                occurrences = visible.filter { it.overlapsDate(d) },
                holidays = if (CalendarLayer.Holidays in layers) SwedishHolidays.forDate(d) else emptyList(),
                custody = if (CalendarLayer.Custody in layers) custody.mapNotNull { CustodyCalculator.householdOn(it, d) } else emptyList(),
                redDay = SwedishHolidays.isRedDay(d),
            )
        }
        CalendarUiState(w.view, w.anchor, clock.todayIn(zone), days, people, filter, layers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun setView(view: CalendarView) {
        viewModelScope.launch { prefs.set(Prefs.CalendarViewMode, view) }
    }

    fun showDay(date: LocalDate) {
        anchor.value = date
        setView(CalendarView.Day)
    }

    fun goToday() {
        anchor.value = clock.todayIn(zone)
    }

    fun step(forward: Boolean) {
        val a = anchor.value
        val sign = if (forward) 1 else -1
        anchor.value = when (state.value.view) {
            CalendarView.Day -> a.plusDays(sign)
            CalendarView.Week -> a.plusDays(7 * sign)
            CalendarView.Month -> if (forward) a.plus(1, DateTimeUnit.MONTH) else a.minus(1, DateTimeUnit.MONTH)
            CalendarView.Agenda -> a.plusDays(AGENDA_DAYS * sign)
        }
    }

    fun togglePerson(id: String) {
        viewModelScope.launch { prefs.update(Prefs.CalendarPersonFilter) { if (id in it) it - id else it + id } }
    }

    fun clearFilter() {
        viewModelScope.launch { prefs.set(Prefs.CalendarPersonFilter, emptySet()) }
    }

    fun toggleLayer(layer: CalendarLayer) {
        viewModelScope.launch { prefs.update(Prefs.CalendarLayers) { if (layer in it) it - layer else it + layer } }
    }

    companion object {
        const val AGENDA_DAYS = 42

        /** Visible date range for a view. Weeks start on Monday; month grids show whole weeks. */
        fun rangeFor(view: CalendarView, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
            CalendarView.Day -> anchor to anchor
            CalendarView.Week -> anchor.startOfWeek() to anchor.startOfWeek().plusDays(6)
            CalendarView.Month -> {
                val first = LocalDate(anchor.year, anchor.month, 1)
                val start = first.startOfWeek()
                val last = first.plus(1, DateTimeUnit.MONTH).plusDays(-1)
                start to last.startOfWeek().plusDays(6)
            }
            CalendarView.Agenda -> anchor to anchor.plusDays(AGENDA_DAYS)
        }
    }
}

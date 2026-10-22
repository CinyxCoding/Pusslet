package se.pusslet.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.CalendarView
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.HandoffAssigner
import kotlin.time.Clock

/**
 * The calendar tab (ADR-033): "Vecka | Månad". Day is its own screen ([CalendarDayViewModel]); stored values from the old
 * Day and Agenda segments open the week.
 */
data class CalendarUiState(
    /** Only [CalendarView.Week] or [CalendarView.Month]. */
    val view: CalendarView = CalendarView.Week,
    val anchor: LocalDate? = null,
    val today: LocalDate? = null,
    val days: List<CalendarDay> = emptyList(),
    val people: List<FamilyPerson> = emptyList(),
    val personFilter: Set<String> = emptySet(),
    val layers: Set<CalendarLayer> = CalendarLayer.entries.toSet(),
    /** Caregivers see what is shared with them but cannot add events or take hand-offs (UX plan 1.13). */
    val role: Role = Role.Parent,
    val myMemberId: String? = null,
    /** Month: the day shown under the grid. Defaults to today in the current month, else the first of the month. */
    val selected: LocalDate? = null,
    /** Week: where the children are, the line under the week ("Hos Ahmed (mån–sön)"). */
    val custodyWeek: List<CustodyWeek> = emptyList(),
) {
    val canEdit: Boolean get() = role == Role.Parent

    /** Layers that are switched off in the filter sheet. */
    val hiddenLayers: List<CalendarLayer> get() = CalendarLayer.entries.filter { it !in layers }

    /** People in the filter, children first ("Bara Simon"). */
    val filteredPeople: List<FamilyPerson> get() = people.filter { it.id in personFilter }.sortedBy { !it.isChild }

    /** Any filter on: the filter icon gets a dot and the line "Bara Simon · Visa alla" shows (UX plan, Kalender 3). */
    val filterActive: Boolean get() = personFilter.isNotEmpty() || hiddenLayers.isNotEmpty()

    /** The shown week or month contains today, so "Idag" is not offered. */
    val showsToday: Boolean
        get() {
            val a = anchor ?: return true
            val t = today ?: return true
            return if (view == CalendarView.Month) a.year == t.year && a.month == t.month else a.startOfWeek() == t.startOfWeek()
        }

    /** The day under the month grid. */
    val selectedDay: CalendarDay? get() = selected?.let { s -> days.firstOrNull { it.date == s } }

    /**
     * The day the round + hands to quick add (UX plan 1.2): the Monday of another week, or the selected day of the month;
     * null when that is today, so that quick add uses today.
     */
    val addDate: LocalDate?
        get() {
            val a = anchor ?: return null
            val t = today ?: return null
            return when (view) {
                CalendarView.Month -> selected?.takeIf { it != t }
                else -> a.startOfWeek().takeIf { it != t.startOfWeek() }
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val assigner: HandoffAssigner,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val anchor = MutableStateFlow(clock.todayIn(zone))

    /** The view chosen in this visit, ahead of the stored one (shown at once, before the store answers). */
    private val chosenView = MutableStateFlow<CalendarView?>(null)

    /** A day tapped in the month; null = the default (today, or the first of another month). */
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    private data class Window(val view: CalendarView, val anchor: LocalDate, val from: LocalDate, val to: LocalDate)

    private data class Settings(val filter: Set<String>, val layers: Set<CalendarLayer>, val role: Role, val me: String?)

    private val window = combine(prefs.observe(Prefs.CalendarViewMode), chosenView, anchor) { stored, chosen, a ->
        val view = shown(chosen ?: stored)
        val (from, to) = rangeFor(view, a)
        Window(view, a, from, to)
    }

    // A caregiver's accepted answer counts at once, before a parent's phone has written it into the event (ADR-030), and a
    // sick child is absent that day (ADR-047). The sick days come along for the "Ella sjuk" lines.
    private val occurrences = window.flatMapLatest { w ->
        combine(calendar.occurrences(w.from, w.to, zone), calendar.handoffRequests, calendar.sickDays, family.children) {
            occ,
            requests,
            sick,
            kids,
            ->
            SickDays.apply(EffectiveHandoffs.apply(occ, requests), sick, kids.map { it.id }.toSet()).occurrences to sick
        }
    }

    private val settings = combine(
        prefs.observe(Prefs.CalendarPersonFilter),
        prefs.observe(Prefs.CalendarLayers),
        sessions.session,
    ) { f, l, s -> Settings(f, l, s.role, s.memberId) }

    private val content = combine(window, occurrences, family.people, family.custody, settings) { w, occSick, people, custody, s ->
        val (occ, sick) = occSick
        val filter = CalendarDays.effectiveFilter(s.filter, people)
        val days = CalendarDays.build(w.from, w.to, occ, people, custody, filter, s.layers, s.me, sick)
        val custodyWeek = if (w.view == CalendarView.Week) {
            CalendarDays.custodyWeek(w.from, w.to, custody, people, filter, s.layers)
        } else {
            emptyList()
        }
        val today = clock.todayIn(zone)
        CalendarUiState(
            view = w.view,
            anchor = w.anchor,
            today = today,
            days = days,
            people = people,
            personFilter = filter,
            layers = s.layers,
            role = s.role,
            myMemberId = s.me,
            selected = if (w.view == CalendarView.Month) defaultSelection(w.anchor, today) else null,
            custodyWeek = custodyWeek,
        )
    }

    val state: StateFlow<CalendarUiState> = combine(content, selectedDate) { s, picked ->
        // A tapped day wins while it is inside the shown month grid.
        val chosen = picked?.takeIf { p -> s.view == CalendarView.Month && s.days.any { it.date == p } }
        if (chosen != null) s.copy(selected = chosen) else s
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarUiState(role = sessions.session.value.role, myMemberId = sessions.session.value.memberId),
    )

    /** An explicit view switch: shown right away and stored as the view the calendar opens with. */
    fun setView(view: CalendarView) {
        val shown = shown(view)
        chosenView.value = shown
        selectedDate.value = null
        viewModelScope.launch { prefs.set(Prefs.CalendarViewMode, shown) }
    }

    /** Month: show [date] under the grid. The calendar stays in the month (UX plan, Kalender 5). */
    fun selectDay(date: LocalDate) {
        selectedDate.value = date
    }

    fun goToday() {
        anchor.value = clock.todayIn(zone)
        selectedDate.value = null
    }

    fun step(forward: Boolean) {
        val a = anchor.value
        anchor.value = when (state.value.view) {
            CalendarView.Month -> if (forward) a.plus(1, DateTimeUnit.MONTH) else a.minus(1, DateTimeUnit.MONTH)
            else -> a.plusDays(if (forward) 7 else -7)
        }
        selectedDate.value = null
    }

    fun togglePerson(id: String) {
        viewModelScope.launch { prefs.update(Prefs.CalendarPersonFilter) { if (id in it) it - id else it + id } }
    }

    /** "Alla" in the filter sheet: everyone again, the layers stay as they are. */
    fun clearPeople() {
        viewModelScope.launch { prefs.set(Prefs.CalendarPersonFilter, emptySet()) }
    }

    fun toggleLayer(layer: CalendarLayer) {
        viewModelScope.launch { prefs.update(Prefs.CalendarLayers) { if (layer in it) it - layer else it + layer } }
    }

    /** "Visa alla": everyone and every layer. */
    fun showAll() {
        viewModelScope.launch {
            prefs.set(Prefs.CalendarPersonFilter, emptySet())
            prefs.set(Prefs.CalendarLayers, CalendarLayer.entries.toSet())
        }
    }

    /** "Jag tar det" on a routine line; parents only. */
    fun take(occurrence: EventOccurrence, kind: HandoffKind) {
        val me = sessions.session.value.memberId ?: return
        if (!state.value.canEdit) return
        viewModelScope.launch { takeHandoff(calendar, assigner, clock, occurrence, kind, me) }
    }

    companion object {
        /** The calendar shows weeks and months; the stored Day and Agenda values from before ADR-033 open the week. */
        fun shown(view: CalendarView): CalendarView = if (view == CalendarView.Month) CalendarView.Month else CalendarView.Week

        /** Visible date range for a view. Weeks start on Monday; month grids show whole weeks. */
        fun rangeFor(view: CalendarView, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (shown(view)) {
            CalendarView.Month -> {
                val first = LocalDate(anchor.year, anchor.month, 1)
                val last = first.plus(1, DateTimeUnit.MONTH).plusDays(-1)
                first.startOfWeek() to last.startOfWeek().plusDays(6)
            }
            else -> anchor.startOfWeek() to anchor.startOfWeek().plusDays(6)
        }

        /** Today in the current month, else the first day of the shown month. */
        fun defaultSelection(anchor: LocalDate, today: LocalDate): LocalDate =
            if (anchor.year == today.year && anchor.month == today.month) today else LocalDate(anchor.year, anchor.month, 1)
    }
}

/**
 * "Jag tar det": the signed-in member takes the hand-off for this occurrence (an override for a series) and answers any open
 * request for it (ADR-015). Only family events can be changed.
 */
internal suspend fun takeHandoff(
    calendar: CalendarRepository,
    assigner: HandoffAssigner,
    clock: Clock,
    occurrence: EventOccurrence,
    kind: HandoffKind,
    me: String,
) {
    if (occurrence.source != EventSource.Family) return
    assigner.assign(occurrence, kind, me)
    calendar.handoffRequests.first()
        .filter { it.kind == kind && it.status == RequestStatus.Pending && it.matches(occurrence) }
        .forEach {
            // A parent wrote it into the event directly, so the answer is applied already.
            val now = clock.now()
            calendar.saveHandoffRequest(it.copy(status = RequestStatus.Accepted, respondedBy = me, respondedAt = now, appliedAt = now))
        }
    settleAnswers(calendar, clock, occurrence, kind)
}

/**
 * After a parent wrote a hand-off into the event by hand, an accepted answer for it that no parent's phone has written in
 * yet is marked as written, so EffectiveHandoffs and AcceptedRequestApplier no longer put it back over the new choice.
 */
internal suspend fun settleAnswers(calendar: CalendarRepository, clock: Clock, occurrence: EventOccurrence, kind: HandoffKind) {
    EffectiveHandoffs.pending(calendar.handoffRequests.first())
        .filter { it.kind == kind && it.matches(occurrence) }
        .forEach { calendar.saveHandoffRequest(it.copy(appliedAt = clock.now())) }
}

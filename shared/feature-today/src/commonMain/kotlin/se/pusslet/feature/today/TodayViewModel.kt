package se.pusslet.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Completion
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.Place
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayInput
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.today.TodayPlanner
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.weather.Forecast
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.Sourced
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.usecase.HandoffAssigner
import kotlin.time.Clock

sealed interface WeatherState {
    data object Loading : WeatherState

    data object NoPlace : WeatherState

    data object Unavailable : WeatherState

    data class Ready(val place: Place, val fetchedAt: LocalDateTime, val stale: Boolean) : WeatherState
}

data class TodayUiState(
    val loading: Boolean = true,
    val plan: TodayPlan? = null,
    val familyName: String = "",
    val me: FamilyPerson? = null,
    val people: Map<String, FamilyPerson> = emptyMap(),
    val weather: WeatherState = WeatherState.Loading,
    val now: LocalDateTime? = null,
    /**
     * Parents can change things from Idag. Caregivers (morfar) only get what the rules let them do, so no "Jag tar det",
     * "Påminn mig", "Planera middag", weather place or + (UX plan 1.13).
     */
    val canEdit: Boolean = true,
    /** The demo flavor's sample family ("Familjen Lind"), so a tester knows the data is not theirs. */
    val demo: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    meals: MealRepository,
    private val tasks: TaskRepository,
    lunches: LunchRepository,
    private val weatherSource: WeatherSource,
    private val sessions: SessionProvider,
    private val handoffs: HandoffAssigner,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val log = Logger.withTag("Today")

    /** Ticks every minute so greetings, "now" and the date roll over by themselves. */
    private val now: Flow<LocalDateTime> = flow {
        while (true) {
            emit(clock.now().toLocalDateTime(zone))
            delay(TICK_MS)
        }
    }
    private val today = now.map { it.date }.distinctUntilChanged()

    private val forecast = MutableStateFlow<Sourced<Forecast>?>(null)
    private val weatherState = MutableStateFlow<WeatherState>(WeatherState.Loading)
    private var lastPlace: Place? = null

    private data class Household(
        val people: List<FamilyPerson>,
        val children: List<se.pusslet.core.model.Child>,
        val familyName: String,
        val custody: List<se.pusslet.core.model.CustodySchedule>,
        val session: Session,
    )

    private val household: Flow<Household> = combine(
        family.people,
        family.children,
        family.family,
        family.custody,
        sessions.session,
    ) { p, c, f, cu, s ->
        Household(p, c, f?.name.orEmpty(), cu, s)
    }

    private val occurrences: Flow<List<EventOccurrence>> = today.flatMapLatest { d ->
        calendar.occurrences(d, d.plusDays(HORIZON_DAYS), zone)
    }

    private data class Extras(
        val rules: List<se.pusslet.core.model.PackingRule>,
        val checks: Map<String, se.pusslet.core.model.PackingChecks>,
        val weeks: List<se.pusslet.core.model.MealPlanWeek>,
        val lunches: Map<String, List<se.pusslet.core.model.SchoolLunch>>,
        val tasks: List<FamilyTask>,
    )

    private val extras: Flow<Extras> = combine(packing.rules, packing.checks, meals.weeks, lunches.lunches, tasks.tasks) { r, c, w, l, t ->
        Extras(r, c, w, l, t)
    }

    val state: StateFlow<TodayUiState> = combine(
        now,
        household,
        occurrences,
        extras,
        combine(forecast, weatherState) { f, w ->
            f to w
        },
    ) { n, h, occ, ex, fw ->
        val me = h.session.memberId
        val plan = TodayPlanner.build(
            TodayInput(
                today = n.date,
                now = n.time,
                zone = zone,
                me = me,
                people = h.people,
                children = h.children,
                occurrences = occ,
                packingRules = ex.rules,
                packingChecks = ex.checks,
                custody = h.custody,
                mealWeeks = ex.weeks.filter { it.week == n.date.isoWeek || it.week == n.date.plusDays(HORIZON_DAYS).isoWeek },
                lunches = ex.lunches,
                tasks = ex.tasks,
                forecast = fw.first?.value,
            ),
        )
        TodayUiState(
            loading = false,
            plan = plan,
            familyName = h.familyName,
            me = h.people.firstOrNull { it.id == me },
            people = h.people.associateBy { it.id },
            weather = fw.second,
            now = n,
            canEdit = h.session.canEdit,
            demo = h.session.demo,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        // Known before anything loads, so a caregiver never sees the + flash by.
        TodayUiState(canEdit = sessions.session.value.canEdit),
    )

    private val Session.canEdit: Boolean get() = role == Role.Parent

    init {
        viewModelScope.launch {
            family.settings.map { it.weatherPlace }.distinctUntilChanged().collect { place ->
                lastPlace = place
                loadWeather(place, force = false)
            }
        }
        // Refresh the forecast hourly while the screen is alive (the source caches for an hour anyway).
        viewModelScope.launch {
            while (true) {
                delay(WEATHER_REFRESH_MS)
                loadWeather(lastPlace, force = false)
            }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch { loadWeather(lastPlace, force = true) }
    }

    private suspend fun loadWeather(place: Place?, force: Boolean) {
        if (place == null) {
            weatherState.value = WeatherState.NoPlace
            return
        }
        val result = weatherSource.forecast(place, force)
        if (result == null) {
            if (forecast.value == null) weatherState.value = WeatherState.Unavailable
            return
        }
        forecast.value = result
        weatherState.value = WeatherState.Ready(place, result.fetchedAt.toLocalDateTime(zone), result.stale)
    }

    fun setPacked(date: LocalDate, item: PackingItem, checked: Boolean) {
        viewModelScope.launch { packing.setChecked(date, item.childId, item.key, checked) }
    }

    fun setTaskDone(occurrence: TaskOccurrence, done: Boolean) {
        val me = sessions.session.value.memberId
        viewModelScope.launch {
            tasks.update(occurrence.task.id) { t ->
                if (t.rrule != null && occurrence.date != null) {
                    val key = occurrence.date.toString()
                    t.copy(completions = if (done) t.completions + (key to Completion(me, clock.now())) else t.completions - key)
                } else {
                    t.copy(done = done, doneAt = if (done) clock.now() else null, doneBy = if (done) me else null)
                }
            }
        }
    }

    /** "Jag tar det": assign the current user to a hand-off in one tap. */
    fun takeHandoff(occurrence: EventOccurrence, kind: HandoffKind) {
        val session = sessions.session.value
        // The rules refuse a caregiver's write; the local copy would show something the family never sees.
        if (!session.canEdit) return
        val me = session.memberId ?: return
        viewModelScope.launch { handoffs.assign(occurrence, kind, me) }
    }

    /** "Kalas på lördag – present är inte köpt": create the to-do linked to the party. */
    fun addGiftTask(occurrence: EventOccurrence, title: String) {
        val session = sessions.session.value
        if (!session.canEdit) return
        val me = session.memberId
        viewModelScope.launch {
            val task = FamilyTask(
                id = ids.newId(),
                title = title,
                category = TaskCategory.Gift,
                assigneeId = me,
                due = occurrence.start.date.plusDays(-1),
                linkedEventId = occurrence.event.id,
                createdBy = me,
                createdAt = clock.now(),
            )
            tasks.save(task)
            log.i { "Gift task created for a party" }
        }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val WEATHER_REFRESH_MS = 60 * 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val HORIZON_DAYS = 10
    }
}

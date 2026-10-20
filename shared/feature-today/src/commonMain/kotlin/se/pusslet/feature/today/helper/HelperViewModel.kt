package se.pusslet.feature.today.helper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.helper.HelperMission
import se.pusslet.core.helper.HelperPlan
import se.pusslet.core.helper.HelperRequest
import se.pusslet.core.model.Completion
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.Place
import se.pusslet.core.model.Role
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayout
import se.pusslet.core.today.TodayLayouts
import se.pusslet.core.today.TodayShopping
import se.pusslet.core.weather.Forecast
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.sync.SyncState
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.data.usecase.HintIds
import se.pusslet.feature.today.ShoppingSnapshot
import se.pusslet.feature.today.TodayModel
import kotlin.time.Clock

/**
 * Everything the helper's home screen draws (UX plan Steg 3). The requests and "Idag" can never be hidden; "Ska med",
 * "Att göra", "Kommande", the weather line and the shopping row follow the viewer's choices (Prefs.TodayLayout, the same
 * model as Idag, with the caregiver's default).
 */
data class HelperUiState(
    val loading: Boolean = true,
    val plan: HelperPlan? = null,
    val me: FamilyPerson? = null,
    /** The parents, named on "Säg till Sara och Johan". */
    val parents: List<FamilyPerson> = emptyList(),
    val layout: TodayLayout = TodayLayouts.defaultFor(Role.Caregiver),
    /** "Ska med" first, then "Att göra" and "Kommande" in the viewer's order: switched on and with something to show. */
    val sections: List<TodayBlock> = emptyList(),
    /** "Regn vid hämtningen, 9°" is switched on. */
    val showWeather: Boolean = true,
    /** "Inköpslistan · 5 kvar"; only while that block is on (it is off by default). */
    val shopping: TodayShopping? = null,
    /** Answers wait on this phone until it is online again ("Skickas när du har nät"). */
    val waitingForNetwork: Boolean = false,
    /** The first-time hint "Här ser du när du hämtar och lämnar." (UX plan 4.7), until "Jag förstår". */
    val showHint: Boolean = false,
    val now: LocalDateTime? = null,
)

/**
 * The simple helper view for caregivers (morfar Bengt, ADR-029): his own drop-offs and pick-ups, questions to him and the
 * few things he may do – answer, say "Jag kan inte", tick what is packed and shared tasks. Every write is one the rules
 * allow a caregiver (ADR-015, ADR-030); nothing here writes events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelperViewModel(
    family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    private val tasks: TaskRepository,
    shopping: ShoppingRepository,
    prefs: PreferencesRepository,
    private val weatherSource: WeatherSource,
    private val sessions: SessionProvider,
    private val requests: HandoffRequests,
    private val hints: FirstTimeHints,
    syncStatus: Flow<SyncStatus>,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val log = Logger.withTag("Helper")

    private val now: Flow<LocalDateTime> = HelperPlans.minutes(clock, zone)
    private val today = now.map { it.date }.distinctUntilChanged()

    // The same reading as the parents' preview (HelperPreviewViewModel), so both show the same plan.
    private val data: Flow<HelperFamilyData> = HelperPlans.familyData(family, calendar, packing, tasks, today, zone)

    /** The viewer's choices; another member or a role change falls back to the role's default. */
    private val layout: Flow<TodayLayout> = combine(sessions.session, prefs.observe(Prefs.TodayLayout)) { s, stored ->
        TodayLayouts.resolve(s.role, s.memberId, stored)
    }.distinctUntilChanged()

    /** The shopping list is only read while "Inköpslistan" is switched on. */
    private val shoppingSnapshot: Flow<ShoppingSnapshot?> = layout.map { it.isOn(TodayBlock.Shopping) }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (!on) {
                flowOf(null)
            } else {
                combine(shopping.lists, shopping.items, shopping.presence, prefs.observe(Prefs.ActiveShoppingList)) { l, i, p, selected ->
                    TodayModel.snapshot(l, i, p, selected)
                }
            }
        }

    /**
     * The forecast for the family's place while the weather line is on, refreshed hourly (the source caches for an hour).
     * It fails soft: without a forecast there is simply no line.
     */
    private val forecast: Flow<Forecast?> = combine(
        layout.map { it.isOn(TodayBlock.Weather) }.distinctUntilChanged(),
        family.settings.map { it.weatherPlace }.distinctUntilChanged(),
    ) { on, place -> place.takeIf { on } }
        .distinctUntilChanged()
        .flatMapLatest { place -> if (place == null) flowOf(null) else forecastFor(place) }
        .onStart { emit(null) }

    private fun forecastFor(place: Place): Flow<Forecast?> = flow {
        while (true) {
            emit(weatherSource.forecast(place, force = false)?.value)
            delay(WEATHER_REFRESH_MS)
        }
    }

    private data class View(
        val layout: TodayLayout,
        val shopping: ShoppingSnapshot?,
        val forecast: Forecast?,
        val waitingForNetwork: Boolean,
        val showHint: Boolean,
    )

    private val view: Flow<View> = combine(
        layout,
        shoppingSnapshot,
        forecast,
        syncStatus.map { it.waitsForNetwork() }.distinctUntilChanged().onStart { emit(false) },
        hints.shouldShow(HintIds.HELPER_HOME),
        ::View,
    )

    val state: StateFlow<HelperUiState> = combine(now, sessions.session, data, view, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HelperUiState())

    private fun build(n: LocalDateTime, session: Session, data: HelperFamilyData, v: View): HelperUiState {
        val me = session.memberId ?: return HelperUiState(loading = false, layout = v.layout, now = n)
        val people = data.people.associateBy { it.id }
        val plan = HelperPlans.plan(me, n, zone, data, v.forecast)
        return HelperUiState(
            loading = false,
            plan = plan,
            me = people[me],
            parents = data.people.filter { !it.isChild && it.role == Role.Parent },
            layout = v.layout,
            sections = sections(v.layout, plan),
            showWeather = v.layout.isOn(TodayBlock.Weather),
            shopping = TodayModel.shopping(v.shopping, people, me, clock.now()),
            waitingForNetwork = v.waitingForNetwork,
            showHint = v.showHint,
            now = n,
        )
    }

    /** "Jag förstår" on the first-time hint: it does not come back on this phone. */
    fun dismissHint() {
        viewModelScope.launch { hints.dismiss(HintIds.HELPER_HOME) }
    }

    /** "Ja, jag hämtar Ella". Answers the latest copy of the request, so a change from another phone is not overwritten. */
    fun accept(request: HelperRequest) = answer(request) { requests.accept(it) }

    /** "Nej, jag kan inte": declined if it was to me, otherwise only my no is recorded and the others can still answer. */
    fun decline(request: HelperRequest) = answer(request) { requests.decline(it) }

    /** "Ändra svar": open again, until a parent's phone has written the answer in. */
    fun changeAnswer(request: HelperRequest) = answer(request) { requests.changeAnswer(it) }

    private fun answer(request: HelperRequest, action: suspend (HandoffRequest) -> Boolean) {
        viewModelScope.launch {
            val latest = calendar.handoffRequests.first().firstOrNull { it.id == request.request.id } ?: return@launch
            if (!action(latest)) log.i { "A hand-off answer was not saved: the request changed meanwhile" }
        }
    }

    /** "Säg till Sara och Johan": asks the family to take over the chosen missions, one request each. */
    fun cannotMake(missions: List<HelperMission>) {
        if (missions.isEmpty()) return
        viewModelScope.launch {
            val asked = missions.count { requests.cannotMake(it.occurrence, it.kind) != null }
            log.i { "Asked the family to take over $asked hand-offs" }
        }
    }

    fun setPacked(date: LocalDate, item: PackingItem, checked: Boolean) {
        viewModelScope.launch { packing.setChecked(date, item.childId, item.key, checked) }
    }

    /** Ticks a shared task: a recurring one for its day, a one-off one for good. */
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

    internal companion object {
        const val WEATHER_REFRESH_MS = 60 * 60_000L
        const val STOP_TIMEOUT_MS = 5_000L

        /** "Ska med" first (it belongs to the morning), then "Att göra" and "Kommande" in the viewer's order. */
        fun sections(layout: TodayLayout, plan: HelperPlan): List<TodayBlock> = buildList {
            if (layout.isOn(TodayBlock.Packing) && plan.packing.any { it.items.isNotEmpty() }) add(TodayBlock.Packing)
            layout.sections.filter { it.visible }.forEach { item ->
                when (item.block) {
                    TodayBlock.Tasks -> if (plan.tasks.isNotEmpty()) add(TodayBlock.Tasks)
                    TodayBlock.Upcoming -> if (plan.upcoming.isNotEmpty()) add(TodayBlock.Upcoming)
                    // The shopping list is a row at the bottom; the other blocks are not sections of the helper view.
                    else -> Unit
                }
            }
        }

        /** Offline, or answers waiting while the phone cannot reach the server. A family without a backend never waits. */
        fun SyncStatus.waitsForNetwork(): Boolean =
            state == SyncState.Offline || (pendingWrites > 0 && (state == SyncState.Connecting || state == SyncState.Error))
    }
}

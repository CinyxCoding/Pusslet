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
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.breaks.Breaks
import se.pusslet.core.breaks.SchoolBreak
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.Completion
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.Family
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.Place
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.SickDay
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.VabPart
import se.pusslet.core.sick.SickDays
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.HandoffStop
import se.pusslet.core.today.PickedUp
import se.pusslet.core.today.Pickup
import se.pusslet.core.today.Pickups
import se.pusslet.core.today.SetupChecklist
import se.pusslet.core.today.SetupInput
import se.pusslet.core.today.SetupProgress
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayout
import se.pusslet.core.today.TodayLayouts
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.today.TodayPlanner
import se.pusslet.core.today.UnconfirmedPickup
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.weather.Forecast
import se.pusslet.data.AppCoordinator
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.MedicineRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.SickDayRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.Sourced
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.sync.SyncLocalStore
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.HintIds
import se.pusslet.data.usecase.PickupReceipts
import se.pusslet.data.usecase.TodayInputSource
import se.pusslet.feature.today.medicine.MedicineDoseRow
import se.pusslet.feature.today.medicine.MedicineModel
import kotlin.time.Clock

sealed interface WeatherState {
    data object Loading : WeatherState

    data object NoPlace : WeatherState

    data object Unavailable : WeatherState

    data class Ready(val place: Place, val fetchedAt: LocalDateTime, val stale: Boolean) : WeatherState
}

/**
 * Everything Idag draws (UX plan, Idag): the header and "Att lösa" ([TodayPlan.problems], [requests]) can never be hidden;
 * Morgonkollen ([hero]) and the [sections] follow the viewer's [layout].
 */
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
    /** The viewer's Idag: Prefs.TodayLayout merged with the role's default (`TodayLayouts.resolve`). */
    val layout: TodayLayout = TodayLayouts.defaultFor(Role.Parent),
    /** Morgonkollen rows, fixed order, at most five; empty = no card. */
    val hero: List<HeroRow> = emptyList(),
    /** Kvällskollen ("Imorgon, onsdag") from 18:00; empty before that or when tomorrow has nothing. */
    val tomorrow: List<HeroRow> = emptyList(),
    /** The sections with something to show, in the viewer's order. */
    val sections: List<TodayBlock> = emptyList(),
    /** Unanswered hand-off requests to the viewer ("Sara frågar om du kan hämta Ella fre 15:45 · Svara"). */
    val requests: List<TodayRequest> = emptyList(),
    /** No section has anything to show: "Inget mer idag". */
    val quiet: Boolean = false,
    /**
     * "Kom igång" (UX plan 4.4): parents of a new real family, until every step is done or "Dölj". While it shows it is the
     * focus surface and Morgonkollen is drawn flat under it.
     */
    val setup: SetupProgress? = null,
    /** The first-time hint about + (UX plan 4.7): once per phone, parents only, never while "Kom igång" shows. */
    val showAddHint: Boolean = false,
    /** "Börja på riktigt" is running (UX plan 4.8). */
    val leavingDemo: Boolean = false,
    /** Leaving the example family did not work: a quiet line says so and the button stays. */
    val leaveDemoFailed: Boolean = false,
    /** Sick days (ADR-047): "Ella är sjuk idag", "Fortfarande sjuk?", VAB to report. Parents only. */
    val sick: SickToday = SickToday(),
    /** "Medicin idag" (ADR-050): today's planned doses of every course. Parents only. */
    val medicine: List<MedicineDoseRow> = emptyList(),
    /** A school break soon with days or a booking to sort out (ADR-051). Parents only. */
    val breaks: List<SchoolBreak> = emptyList(),
    /** Somebody else's pick-up nobody ticked off (ADR-048); empty when the warning is switched off. */
    val unconfirmedPickups: List<UnconfirmedPickup> = emptyList(),
    /** Today's ticked-off pick-ups. */
    val pickedUp: List<PickedUp> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    family: FamilyRepository,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    meals: MealRepository,
    private val tasks: TaskRepository,
    lunches: LunchRepository,
    shopping: ShoppingRepository,
    private val prefs: PreferencesRepository,
    private val weatherSource: WeatherSource,
    private val sessions: SessionProvider,
    private val handoffs: HandoffAssigner,
    private val hints: FirstTimeHints,
    private val coordinator: AppCoordinator,
    private val local: SyncLocalStore,
    private val auth: AuthRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
    private val sick: SickDayRepository,
    private val receipts: PickupReceipts,
    private val medicine: MedicineRepository,
) : ViewModel() {
    private val log = Logger.withTag("Today")

    /** Ticks every minute so "now", passed rows and the date roll over by themselves. */
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

    /** Idag's data, read the same way as the notification plan reads it. */
    private val source = TodayInputSource(family, calendar, packing, meals, tasks, lunches, sessions, clock, zone)

    private val household: Flow<TodayInputSource.Household> = source.household

    /** The day's occurrences with sick children absent, and what their sick day cancelled (ADR-047). */
    private val day: Flow<SickDays.Applied> = today.flatMapLatest { d -> source.day(d) }

    private val extras: Flow<TodayInputSource.Extras> = source.extras

    /** The viewer's layout; another member or a role change falls back to the role's default. */
    private val layout: Flow<TodayLayout> = combine(sessions.session, prefs.observe(Prefs.TodayLayout)) { s, stored ->
        TodayLayouts.resolve(s.role, s.memberId, stored)
    }.distinctUntilChanged()

    /** The shopping list is only read while "Inköpslistan" is switched on (it is off by default). */
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
     * Loads the forecast for the family's place and refreshes it hourly (the source caches for an hour anyway). It runs only
     * while someone looks at Idag, so nothing polls SMHI from the back stack and a test's virtual clock can go idle. Starts
     * with a value, so Idag never waits for the forecast.
     */
    private val weatherLoader: Flow<Unit> = family.settings.map { it.weatherPlace }
        .distinctUntilChanged()
        .flatMapLatest { place ->
            flow {
                lastPlace = place
                loadWeather(place, force = false)
                emit(Unit)
                while (place != null) {
                    delay(WEATHER_REFRESH_MS)
                    loadWeather(place, force = false)
                    emit(Unit)
                }
            }
        }
        .onStart { emit(Unit) }

    private enum class DemoExit { Idle, Leaving, Failed }

    private val demoExit = MutableStateFlow(DemoExit.Idle)

    /** What "Kom igång" needs beyond Idag's own data. */
    private data class SetupData(val family: Family?, val members: List<Member>, val events: List<Event>, val hasShoppingItems: Boolean)

    /** Only read for a parent of a real family that has not hidden "Kom igång" on this phone; everyone else gets null. */
    private val setupData: Flow<SetupData?> = combine(sessions.session, prefs.observe(Prefs.SetupHidden)) { s, hidden ->
        s.role == Role.Parent && !s.demo && s.familyId != null && s.familyId !in hidden
    }.distinctUntilChanged().flatMapLatest { candidate ->
        if (!candidate) {
            flowOf(null)
        } else {
            val hasItems = shopping.items.map { it.isNotEmpty() }.distinctUntilChanged()
            combine(family.family, family.members, calendar.events, hasItems) { f, m, e, items -> SetupData(f, m, e, items) }
        }
    }

    private data class FirstUse(val setup: SetupData?, val hintUnseen: Boolean, val demoExit: DemoExit)

    private val firstUse: Flow<FirstUse> = combine(setupData, hints.shouldShow(HintIds.TODAY_ADD), demoExit) { s, h, d ->
        FirstUse(s, h, d)
    }

    private data class View(
        val pickupWarnings: Boolean,
        val layout: TodayLayout,
        val shopping: ShoppingSnapshot?,
        val requests: List<HandoffRequest>,
        val hasPlace: Boolean,
        val forecast: Sourced<Forecast>?,
        val weather: WeatherState,
        val firstUse: FirstUse,
    )

    private val view: Flow<View> = combine(
        combine(layout, prefs.observe(Prefs.PickupWarnings), ::Pair),
        shoppingSnapshot,
        calendar.handoffRequests,
        family.settings.map { it.weatherPlace != null }.distinctUntilChanged(),
        combine(forecast, weatherState, weatherLoader, firstUse) { f, w, _, u -> Triple(f, w, u) },
    ) { lw, s, r, place, fwu ->
        View(lw.second, lw.first, s, r, place, fwu.first, fwu.second, fwu.third)
    }

    val state: StateFlow<TodayUiState> = combine(now, household, day, extras, view) { n, h, d, ex, v ->
        build(n, h, d, ex, v)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        // Known before anything loads, so a caregiver never sees the + flash by.
        TodayUiState(canEdit = sessions.session.value.canEdit),
    )

    private fun build(
        n: LocalDateTime,
        h: TodayInputSource.Household,
        day: SickDays.Applied,
        ex: TodayInputSource.Extras,
        v: View,
    ): TodayUiState {
        val occ = day.occurrences
        val me = h.session.memberId
        val people = h.people.associateBy { it.id }
        val plan = TodayPlanner.build(
            source.input(
                now = n,
                household = h,
                occurrences = occ,
                extras = ex,
                requests = v.requests,
                forecast = v.forecast?.value,
                hasWeatherPlace = v.hasPlace,
                shopping = TodayModel.shopping(v.shopping, people, me, clock.now()),
            ),
        )
        val canEdit = h.session.canEdit
        val sections = TodayModel.sections(v.layout, plan)
        val setup = v.firstUse.setup?.let { data ->
            SetupChecklist.forIdag(
                SetupInput(
                    today = n.date,
                    now = clock.now(),
                    role = h.session.role,
                    me = me,
                    family = data.family,
                    demo = h.session.demo,
                    members = data.members,
                    children = h.children,
                    events = data.events,
                    occurrences = occ,
                    mealWeeks = ex.weeks,
                    hasShoppingItems = data.hasShoppingItems,
                    canInvite = auth.available,
                ),
            )
        }
        return TodayUiState(
            loading = false,
            plan = plan,
            familyName = h.familyName,
            me = people[me],
            people = people,
            weather = v.weather,
            now = n,
            canEdit = canEdit,
            demo = h.session.demo,
            layout = v.layout,
            hero = TodayModel.heroRows(v.layout, plan, canEdit, weatherLoading = v.weather == WeatherState.Loading),
            tomorrow = TodayModel.tomorrowRows(v.layout, plan, weatherLoading = v.weather == WeatherState.Loading),
            sections = sections,
            // A caregiver's "Jag kan inte" is a problem above ("Bengt kan inte hämta Simon 15:30"), not a question as well.
            requests = TodayModel.requests(me, v.requests, occ, people, n.date, n.time)
                .filter { r -> plan.cannotMake.none { it.request.id == r.request.id } },
            quiet = sections.isEmpty(),
            setup = setup,
            // "Kom igång" already leads to + ("Lägg till en aktivitet"): one thing to learn at a time.
            showAddHint = canEdit && v.firstUse.hintUnseen && setup == null,
            leavingDemo = v.firstUse.demoExit == DemoExit.Leaving,
            leaveDemoFailed = v.firstUse.demoExit == DemoExit.Failed,
            sick = if (canEdit) SickModel.build(n.date, me, ex.sickDays, day.cancelled, people, h.children) else SickToday(),
            medicine = if (canEdit) MedicineModel.today(n.date, ex.medicineCourses, ex.medicineLog, people) else emptyList(),
            breaks = if (canEdit) Breaks.breaks(occ, ex.breakPlans, n.date).filter { Breaks.needsPlan(it, n.date) } else emptyList(),
            unconfirmedPickups = if (v.pickupWarnings) plan.unconfirmedPickups else emptyList(),
            pickedUp = plan.pickedUp,
        )
    }

    private val Session.canEdit: Boolean get() = role == Role.Parent

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
        viewModelScope.launch {
            handoffs.assign(occurrence, kind, me)
            // Taking it answers an open request for it, e.g. a caregiver's "Jag kan inte" (written in, so applied too).
            val now = clock.now()
            calendar.handoffRequests.first()
                .filter { it.kind == kind && it.status == RequestStatus.Pending && it.matches(occurrence) }
                .forEach {
                    val answered = it.copy(status = RequestStatus.Accepted, respondedBy = me, respondedAt = now, appliedAt = now)
                    calendar.saveHandoffRequest(answered)
                }
        }
    }

    /** "Dölj" on "Kom igång": it does not come back on this phone for this family. */
    fun hideSetup() {
        val familyId = sessions.session.value.familyId ?: return
        viewModelScope.launch { prefs.update(Prefs.SetupHidden) { it + familyId } }
    }

    /** "Jag förstår" on the hint about +: it does not come back on this phone. */
    fun dismissAddHint() {
        viewModelScope.launch { hints.dismiss(HintIds.TODAY_ADD) }
    }

    /**
     * "Börja på riktigt" (UX plan 4.8), after the dialog: removes the example family from this phone and resets onboarding,
     * like "Visa första start igen"; the shell then shows the welcome screen. Only for the example family. A failure is kept
     * in the state ([TodayUiState.leaveDemoFailed]) so the screen can say so; the button stays for another try.
     */
    fun leaveDemo() {
        if (!sessions.session.value.demo || demoExit.value == DemoExit.Leaving) return
        demoExit.value = DemoExit.Leaving
        viewModelScope.launch {
            val left = coordinator.replayOnboarding(local)
            if (!left) log.w { "Could not leave the example family" }
            demoExit.value = if (left) DemoExit.Idle else DemoExit.Failed
        }
    }

    // region Pick-up receipts (ADR-048)

    /** "Hämtad" on my own line: the stops that are due, one receipt per occurrence. */
    fun pickedUp(stops: List<HandoffStop>) {
        val now = clock.now().toLocalDateTime(zone).time
        val due = stops.filter { Pickups.confirmable(it.time, now) }.groupBy { it.occurrence.key }
        viewModelScope.launch {
            due.values.forEach { group -> receipts.confirm(group.first().occurrence, HandoffKind.PickUp, group.map { it.child.id }) }
        }
    }

    /** "Hämtad" on somebody else's pick-up: e.g. Sara has spoken to Bengt. */
    fun confirmPickup(pickup: Pickup) {
        viewModelScope.launch { receipts.confirm(pickup.occurrence, HandoffKind.PickUp, pickup.children.map { it.id }) }
    }

    fun undoPickup(receipt: HandoffReceipt) {
        viewModelScope.launch { receipts.undo(receipt) }
    }

    // endregion

    // region Sick days (ADR-047)

    /** "Sjukanmäl": [childIds] are home sick on [date]; their day is cancelled and the others are told (push). */
    fun reportSick(childIds: List<String>, date: LocalDate) = sickWrite { sick.report(childIds, date) }

    /** "Ångra": the report was a mistake. */
    fun undoSick(day: SickDay) = sickWrite { sick.undo(day.id) }

    /** "Vem stannar hemma?": a parent takes VAB by default, another adult (morfar) does not. */
    fun setHomeWith(day: SickDay, memberId: String?) {
        val parent = memberId != null && state.value.people[memberId]?.role == Role.Parent
        sickWrite { sick.update(day.id) { it.copy(homeWith = memberId, vab = parent) } }
    }

    fun setVab(day: SickDay, on: Boolean) = sickWrite { sick.update(day.id) { it.copy(vab = on) } }

    fun setVabPart(day: SickDay, part: VabPart) = sickWrite { sick.update(day.id) { it.copy(vabPart = part) } }

    fun setAbsenceReported(day: SickDay, done: Boolean) = sickWrite { sick.update(day.id) { it.copy(absenceReported = done) } }

    fun setWorkTold(day: SickDay, done: Boolean) = sickWrite { sick.update(day.id) { it.copy(workTold = done) } }

    fun setVabReported(day: SickDay, done: Boolean) = sickWrite { sick.update(day.id) { it.copy(vabReported = done) } }

    /** "Sjuk idag också": today becomes a sick day too, with the same person at home. */
    fun stillSick(previous: SickDay) = sickWrite { sick.stillSick(previous, clock.now().toLocalDateTime(zone).date) }

    /** "Frisk igen". */
    fun wellAgain(previous: SickDay) = sickWrite { sick.wellAgain(previous) }

    /** "Anmält" on "Anmäl VAB för Ella". */
    fun vabReported(report: SickDays.VabToReport) = sickWrite { sick.setVabReported(report.days) }

    /** "Given" on a planned dose (ADR-050): logged for the course, so nobody is reminded of it again. */
    fun giveDose(slot: MedicineRules.Slot) = sickWrite {
        medicine.logDose(slot.course.childId, slot.course.medicine, slot.course.dose, courseId = slot.course.id, slot = slot.at)
    }

    private fun sickWrite(block: suspend () -> Unit) {
        if (!sessions.session.value.canEdit) return
        viewModelScope.launch { block() }
    }

    // endregion

    /** "Kalas på lördag – present?": "Påminn mig" creates the to-do linked to the party, due the day before. */
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
    }
}

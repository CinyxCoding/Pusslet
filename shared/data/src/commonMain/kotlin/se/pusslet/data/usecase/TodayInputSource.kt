package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayInput
import se.pusslet.core.today.TodayShopping
import se.pusslet.core.weather.Forecast
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/**
 * Assembles the [TodayInput] Idag is built from, so Idag (`TodayViewModel`, which adds weather and shopping) and a
 * background worker (the notification plan) read the same data the same way.
 */
class TodayInputSource(
    private val family: FamilyRepository,
    private val calendar: CalendarRepository,
    packing: PackingRepository,
    meals: MealRepository,
    tasks: TaskRepository,
    lunches: LunchRepository,
    sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    /** Which calendar layers to read. */
    enum class Layers {
        /** What Kalender's filter shows on this phone (Idag follows it). */
        Shown,

        /** Every layer, whatever the filter: for what must not depend on a view choice, like reminders (ADR-031). */
        All,
    }

    data class Household(
        val people: List<FamilyPerson>,
        val children: List<Child>,
        val familyName: String,
        val custody: List<CustodySchedule>,
        val session: Session,
    )

    data class Extras(
        val rules: List<PackingRule>,
        val checks: Map<String, PackingChecks>,
        val weeks: List<MealPlanWeek>,
        val lunches: Map<String, List<SchoolLunch>>,
        val tasks: List<FamilyTask>,
        val sickDays: List<SickDay> = emptyList(),
        val receipts: List<HandoffReceipt> = emptyList(),
        val medicineCourses: List<MedicineCourse> = emptyList(),
        val medicineLog: List<MedicineEntry> = emptyList(),
        val breakPlans: List<BreakPlan> = emptyList(),
    )

    // The other home's adults by name (ADR-056): "Lina hämtar Ella", not an unassigned pick-up.
    val household: Flow<Household> = combine(
        family.everyone,
        family.children,
        family.family,
        family.custody,
        sessions.session,
    ) { p, c, f, cu, s ->
        Household(p, c, f?.name.orEmpty(), cu, s)
    }

    private val sickDays: Flow<List<SickDay>> = calendar.sickDays

    val extras: Flow<Extras> = combine(
        combine(packing.rules, packing.checks, meals.weeks, lunches.lunches, tasks.tasks) { r, c, w, l, t -> Extras(r, c, w, l, t) },
        combine(sickDays, calendar.handoffReceipts) { s, r -> s to r },
        calendar.medicineCourses,
        calendar.medicineLog,
        calendar.breakPlans,
    ) { ex, (s, r), courses, log, breaks ->
        ex.copy(sickDays = s, receipts = r, medicineCourses = courses, medicineLog = log, breakPlans = breaks)
    }

    val requests: Flow<List<HandoffRequest>> get() = calendar.handoffRequests

    /**
     * Occurrences from [today] through [HORIZON_DAYS] days ahead. A caregiver's accepted answer counts at once, before a
     * parent's phone has written it into the event (ADR-030), and a sick child is absent that day (ADR-047).
     */
    fun occurrences(
        today: LocalDate,
        layers: Layers = Layers.Shown,
    ): Flow<List<EventOccurrence>> = day(today, layers).map { it.occurrences }

    /** [occurrences] and what a sick child's day cancelled, for Idag's sick card ("Inställt: Dans 17:00"). */
    fun day(today: LocalDate, layers: Layers = Layers.Shown): Flow<SickDays.Applied> {
        val to = today.plusDays(HORIZON_DAYS)
        val occurrences = when (layers) {
            Layers.Shown -> calendar.occurrences(today, to, zone)
            Layers.All -> calendar.allOccurrences(today, to, zone)
        }
        val childIds = family.children.map { list -> list.map { it.id }.toSet() }
        return combine(occurrences, calendar.handoffRequests, sickDays, childIds) { occ, requests, sick, children ->
            SickDays.apply(EffectiveHandoffs.apply(occ, requests), sick, children)
        }
    }

    /** The input for [now]; weather and shopping come from the caller, since only Idag reads them. */
    fun input(
        now: LocalDateTime,
        household: Household,
        occurrences: List<EventOccurrence>,
        extras: Extras,
        requests: List<HandoffRequest>,
        forecast: Forecast? = null,
        hasWeatherPlace: Boolean = forecast != null,
        shopping: TodayShopping? = null,
    ): TodayInput {
        val week = now.date.isoWeek
        // Last week too: "Planera middag" is only offered to a family that planned a dinner this or last week.
        val mealWeeks = setOf(week.previous(), week, now.date.plusDays(HORIZON_DAYS).isoWeek)
        return TodayInput(
            today = now.date,
            now = now.time,
            zone = zone,
            me = household.session.memberId,
            people = household.people,
            children = household.children,
            occurrences = occurrences,
            packingRules = extras.rules,
            packingChecks = extras.checks,
            custody = household.custody,
            mealWeeks = extras.weeks.filter { it.week in mealWeeks },
            lunches = extras.lunches,
            tasks = extras.tasks,
            forecast = forecast,
            hasWeatherPlace = hasWeatherPlace,
            shopping = shopping,
            requests = requests,
            sickDays = extras.sickDays,
            receipts = extras.receipts,
            medicineCourses = extras.medicineCourses,
            medicineLog = extras.medicineLog,
            breakPlans = extras.breakPlans,
        )
    }

    /** One snapshot for right now, without weather or shopping: what a worker plans from. */
    suspend fun current(layers: Layers = Layers.All): TodayInput {
        val now = clock.now().toLocalDateTime(zone)
        return input(now, household.first(), occurrences(now.date, layers).first(), extras.first(), requests.first())
    }

    companion object {
        /** How far ahead occurrences are read: Idag's "Kommande" and the next days' meals. */
        const val HORIZON_DAYS = 10
    }
}

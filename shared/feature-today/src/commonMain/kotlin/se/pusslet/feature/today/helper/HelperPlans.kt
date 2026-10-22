package se.pusslet.feature.today.helper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.helper.HelperInput
import se.pusslet.core.helper.HelperPlan
import se.pusslet.core.helper.HelperPlanner
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.weather.Forecast
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.TaskRepository
import kotlin.time.Clock

/**
 * What the helper view is built from: everything on this phone, unfiltered. [HelperPlanner] applies CaregiverVisibility
 * and EffectiveHandoffs itself, so the caregiver's phone and the parents' preview ("Så ser Bengt appen") pass the same
 * kind of data and get the same answer (ADR-031).
 */
internal data class HelperFamilyData(
    val people: List<FamilyPerson>,
    val children: List<Child>,
    val custody: List<CustodySchedule>,
    /** From today through [HelperPlanner.WEEK_DAYS] days. */
    val occurrences: List<EventOccurrence>,
    val requests: List<HandoffRequest>,
    val rules: List<PackingRule>,
    val checks: Map<String, PackingChecks>,
    val tasks: List<FamilyTask>,
    val sickDays: List<SickDay> = emptyList(),
)

/** Reads and plans the helper view the same way for [HelperViewModel] and [HelperPreviewViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object HelperPlans {
    const val TICK_MS = 60_000L

    /** Ticks every minute so "Snart", "Klart för idag" and the date roll over by themselves (only while observed). */
    fun minutes(clock: Clock, zone: TimeZone): Flow<LocalDateTime> = flow {
        while (true) {
            emit(clock.now().toLocalDateTime(zone))
            delay(TICK_MS)
        }
    }

    /** The family's data from [today] on; the raw requests go in, the planner decides what the caregiver may see. */
    fun familyData(
        family: FamilyRepository,
        calendar: CalendarRepository,
        packing: PackingRepository,
        tasks: TaskRepository,
        today: Flow<LocalDate>,
        zone: TimeZone,
    ): Flow<HelperFamilyData> {
        val household = combine(family.people, family.children, family.custody) { p, c, cu -> Triple(p, c, cu) }
        val calendarData = today.flatMapLatest { d ->
            // What is shared with the caregiver, not what this phone's Kalender filter happens to show.
            combine(calendar.allOccurrences(d, d.plusDays(HelperPlanner.WEEK_DAYS), zone), calendar.handoffRequests) { o, r -> o to r }
        }
        return combine(household, calendarData, packing.rules, packing.checks, combine(tasks.tasks, calendar.sickDays, ::Pair)) {
            h,
            cal,
            rules,
            checks,
            ts,
            ->
            // A sick child stays home: the caregiver's pick-up that day is off (ADR-047).
            val occurrences = SickDays.apply(cal.first, ts.second, h.second.map { it.id }.toSet()).occurrences
            HelperFamilyData(h.first, h.second, h.third, occurrences, cal.second, rules, checks, ts.first, ts.second)
        }
    }

    /** The helper home for [me] at [now]. */
    fun plan(me: String, now: LocalDateTime, zone: TimeZone, data: HelperFamilyData, forecast: Forecast? = null): HelperPlan =
        HelperPlanner.build(
            HelperInput(
                today = now.date,
                now = now.time,
                zone = zone,
                me = me,
                people = data.people,
                children = data.children,
                occurrences = data.occurrences,
                requests = data.requests,
                packingRules = data.rules,
                packingChecks = data.checks,
                custody = data.custody,
                tasks = data.tasks,
                forecast = forecast,
                sickDays = data.sickDays,
            ),
        )
}

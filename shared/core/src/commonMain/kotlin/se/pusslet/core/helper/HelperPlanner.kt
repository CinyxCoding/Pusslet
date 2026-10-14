package se.pusslet.core.helper

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingReason
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.TaskKind
import se.pusslet.core.packing.PackingPlanner
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.ChildPacking
import se.pusslet.core.today.Greeting
import se.pusslet.core.today.TodayPlanner
import se.pusslet.core.weather.Forecast
import se.pusslet.core.weather.WeatherMath
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Everything the helper view (UX plan Steg 3, morfar Bengt) is built from. The planner applies [CaregiverVisibility] and
 * [EffectiveHandoffs] itself, so a caregiver's phone and the parents' preview ("Så ser Bengt appen") can pass what they
 * have and get the same answer.
 */
data class HelperInput(
    val today: LocalDate,
    val now: LocalTime,
    val zone: TimeZone,
    /** The caregiver the view is for. */
    val me: String,
    /** Adults and children (children may also come only through [children]). */
    val people: List<FamilyPerson>,
    val children: List<Child>,
    /** Occurrences from [today] through at least [HelperPlanner.WEEK_DAYS] days. Private ones are filtered out here. */
    val occurrences: List<EventOccurrence>,
    val requests: List<HandoffRequest> = emptyList(),
    val packingRules: List<PackingRule> = emptyList(),
    val packingChecks: Map<String, PackingChecks> = emptyMap(),
    val custody: List<CustodySchedule> = emptyList(),
    /** Only shared tasks are used. */
    val tasks: List<FamilyTask> = emptyList(),
    /** Optional; without it there is no weather line (it fails soft). */
    val forecast: Forecast? = null,
)

/** Where a mission is in the day: "Snart" from 60 minutes before, and quietly done 60 minutes after its time. */
enum class MissionState { Upcoming, Soon, Done }

/** "Ta med: gympapåse" – one thing to bring for one child. */
data class HelperBring(val text: String, val child: FamilyPerson)

/** "Efteråt: Ella har Dans 17:15 · Kulturskolan" – a child's shared event later the same day. */
data class HelperAfter(val child: FamilyPerson, val occurrence: EventOccurrence) {
    val time: LocalTime get() = occurrence.start.time
}

/**
 * One of the caregiver's own drop-offs or pick-ups: "Du hämtar Simon 15:30" with the place under it. The person who
 * actually does it counts (the event plus accepted answers, [EffectiveHandoffs]). [bring], [after] and [state] are only
 * filled in for today.
 */
data class HelperMission(
    val kind: HandoffKind,
    val date: LocalDate,
    val time: LocalTime,
    /** Never empty, in family order. */
    val children: List<FamilyPerson>,
    val occurrence: EventOccurrence,
    /** The event's name ("Fotboll"); null for school, preschool and fritids, whose hand-off is the whole story. */
    val title: String?,
    /** The event's location, else the children's school or preschool for those routines. */
    val place: String?,
    /** At most [HelperPlanner.MAX_BRING]; [moreToBring] says how many more ("och 2 till"). */
    val bring: List<HelperBring> = emptyList(),
    val moreToBring: Int = 0,
    /** At most [HelperPlanner.MAX_AFTER], only after a pick-up. */
    val after: List<HelperAfter> = emptyList(),
    val state: MissionState = MissionState.Upcoming,
    /** "Jag kan inte" was said for this one and nobody has taken it over yet. */
    val waitingForTakeover: Boolean = false,
) {
    val key: String get() = "${occurrence.key}/${kind.name}"
}

/** The caregiver's answer on a request card; null while the request is open. */
enum class HelperAnswer { Yes, No }

/**
 * "Sara undrar: Kan du hämta Ella imorgon, fredag 16:30?". After an answer the card stays where it was ("Du sa ja. Sara ser
 * det.") until a parent's phone has written a yes into the event.
 */
data class HelperRequest(
    val request: HandoffRequest,
    /** Who asks; null when that member has left the family. */
    val from: FamilyPerson?,
    val children: List<FamilyPerson>,
    /** The day of the hand-off: drop-offs on the first day, pick-ups on the last. */
    val date: LocalDate,
    val time: LocalTime?,
    val place: String?,
    val occurrence: EventOccurrence,
    val answer: HelperAnswer?,
) {
    val kind: HandoffKind get() = request.kind
    val isOpen: Boolean get() = answer == null

    /** "Ändra svar" works until a parent's phone has written the answer in (ADR-030). */
    val canChangeAnswer: Boolean get() = answer != null && request.appliedAt == null
}

/** "Johan hämtar Simon i stället": someone took over a hand-off the caregiver could not make today. */
data class HelperTakenOver(
    val kind: HandoffKind,
    /** Null when that member has left the family. */
    val by: FamilyPerson?,
    val children: List<FamilyPerson>,
    val occurrence: EventOccurrence,
)

enum class HelperWeatherCondition { Rain, Snow, Cold }

/** "Regn vid hämtningen, 9°": only for precipitation or below 5° at the time of the first mission still to come. */
data class HelperWeather(val kind: HandoffKind, val condition: HelperWeatherCondition, val temperature: Int)

/**
 * A day of the caregiver's own missions ("Torsdag 15 oktober · vecka 42 – Du hämtar Simon 15:30 och Ella 15:45").
 * [week] is set when the day is in another ISO week than the row before it (the first row compares with today).
 */
data class HelperDay(val date: LocalDate, val week: Int?, val missions: List<HelperMission>)

/** One row of "Hela veckan". */
sealed interface HelperWeekItem {
    val time: LocalTime?

    /** "Du hämtar Simon 15:30", in bold. */
    data class Mission(val mission: HelperMission) : HelperWeekItem {
        override val time: LocalTime get() = mission.time
    }

    /** Anything else shared with the caregiver, except the school and preschool routines. */
    data class Event(val occurrence: EventOccurrence, val people: List<FamilyPerson>) : HelperWeekItem {
        override val time: LocalTime? get() = if (occurrence.allDay) null else occurrence.start.time
    }
}

/** A day in "Hela veckan"; days without anything are left out. [week] as in [HelperDay]. */
data class HelperWeekDay(val date: LocalDate, val week: Int?, val holidays: List<Holiday>, val items: List<HelperWeekItem>)

/** What the helper view shows, already decided (UX plan Steg 3). */
data class HelperPlan(
    val date: LocalDate,
    val week: IsoWeek,
    val greeting: Greeting,
    val holidays: List<Holiday>,
    /** Open requests and the caregiver's own answers that can still change, by when the hand-off is. */
    val requests: List<HelperRequest>,
    /** Today's missions by time, done ones included (see [activeMissions]). */
    val missions: List<HelperMission>,
    val takenOver: List<HelperTakenOver>,
    /** "Nästa gång": the next day with a mission within [HelperPlanner.WEEK_DAYS], only while nothing is left today. */
    val next: HelperDay?,
    val weather: HelperWeather?,
    /** "Ska med": children the caregiver drops off today, plus handover things for children he picks up. */
    val packing: List<ChildPacking>,
    /** "Att göra", at most [HelperPlanner.MAX_TASKS]. */
    val tasks: List<TaskOccurrence>,
    /** "Kommande": days with missions in the next [HelperPlanner.UPCOMING_DAYS] days, at most [HelperPlanner.MAX_UPCOMING]. */
    val upcoming: List<HelperDay>,
    /** "Hela veckan": [HelperPlanner.WEEK_DAYS] days from today with everything shared. */
    val days: List<HelperWeekDay>,
) {
    val activeMissions: List<HelperMission> get() = missions.filter { it.state != MissionState.Done }

    /** "Klart för idag". */
    val allDoneToday: Boolean get() = missions.isNotEmpty() && activeMissions.isEmpty()

    /** The missions "Jag kan inte idag" offers: still to come and not handed over already. */
    val cannotMakeCandidates: List<HelperMission> get() = activeMissions.filter { !it.waitingForTakeover }
}

/**
 * The helper view's rules (UX plan Steg 3): what concerns the caregiver on the home screen, everything shared with him in
 * "Hela veckan". Pure and rule based (ADR-001); the same function serves his phone and the parents' preview.
 */
object HelperPlanner {
    const val WEEK_DAYS = 14
    const val UPCOMING_DAYS = 7
    const val MAX_UPCOMING = 3
    const val MAX_TASKS = 3
    const val MAX_BRING = 3
    const val MAX_AFTER = 2

    /** "Snart" from this many minutes before; done this many minutes after (a late morfar keeps his row). */
    const val SOON_MINUTES = 60

    /** "Efteråt" only lists what starts before this. */
    val AFTER_UNTIL: LocalTime = LocalTime(20, 0)

    /** Below this temperature (°C) the weather line warns about the cold. */
    const val COLD_BELOW = 5.0

    fun build(input: HelperInput): HelperPlan {
        val today = input.today
        val byId = (input.people + input.children.map(FamilyPerson::of)).associateBy { it.id }
        // What the rules let a caregiver read, whatever was passed in: the preview passes the parents' data.
        val readable = CaregiverVisibility.occurrences(input.occurrences)
        val requests = CaregiverVisibility.requests(input.requests, readable)
        val visible = EffectiveHandoffs.apply(readable, requests)
        val waiting = requests.filter { it.fromMemberId == input.me && it.status == RequestStatus.Pending }

        val days = (0 until WEEK_DAYS).map { today.plusDays(it) }
        val plain = days.associateWith { missionsOn(it, visible, input, byId, waiting) }
        val packing = PackingPlanner.itemsFor(
            today,
            input.children,
            input.packingRules,
            visible.filter { it.overlapsDate(today) },
            input.custody,
            input.packingChecks,
        )
        val missions = plain[today].orEmpty().map { enrich(it, input, packing, visible) }
        val active = missions.filter { it.state != MissionState.Done }
        val byDay = plain + (today to missions)

        val next = if (active.isEmpty()) firstDayWithMissions(days.drop(1), byDay, today) else null
        val upcoming = days.drop(1).take(UPCOMING_DAYS)
            .filter { it != next?.date && byDay[it].orEmpty().isNotEmpty() }
            .take(MAX_UPCOMING)
            .let { dates -> dates.zip(weekChanges(dates, today)) { date, week -> HelperDay(date, week, byDay[date].orEmpty()) } }

        return HelperPlan(
            date = today,
            week = today.isoWeek,
            greeting = TodayPlanner.greeting(input.now),
            holidays = SwedishHolidays.forDate(today),
            requests = requestsFor(input, requests, visible, byId),
            missions = missions,
            takenOver = takenOver(input, requests, visible, byId),
            next = next,
            weather = input.forecast?.let { weatherFor(it, active.firstOrNull(), input) },
            packing = packingFor(input, packing, active, byId),
            tasks = tasksFor(input, CaregiverVisibility.tasks(input.tasks), byDay),
            upcoming = upcoming,
            days = weekDays(days, byDay, visible, byId, today),
        )
    }

    /** "Snart" from [SOON_MINUTES] before [time], done [SOON_MINUTES] after it. */
    fun stateAt(time: LocalTime, now: LocalTime): MissionState {
        val at = time.toSecondOfDay() / SECONDS_PER_MINUTE
        val minute = now.toSecondOfDay() / SECONDS_PER_MINUTE
        return when {
            minute >= at + SOON_MINUTES -> MissionState.Done
            minute >= at - SOON_MINUTES -> MissionState.Soon
            else -> MissionState.Upcoming
        }
    }

    /** The caregiver's drop-offs (on an occurrence's first day) and pick-ups (on its last day) on [date], by time. */
    private fun missionsOn(
        date: LocalDate,
        visible: List<EventOccurrence>,
        input: HelperInput,
        byId: Map<String, FamilyPerson>,
        waiting: List<HandoffRequest>,
    ): List<HelperMission> {
        val childOrder = input.children.withIndex().associate { (i, c) -> c.id to i }
        return visible.filter { it.source != EventSource.Holiday && it.overlapsDate(date) }.flatMap { o ->
            val kids = o.event.participantIds.mapNotNull { byId[it] }.filter { it.isChild }
                .sortedBy { childOrder[it.id] ?: Int.MAX_VALUE }
            if (kids.isEmpty()) return@flatMap emptyList()
            listOfNotNull(
                o.dropOffTime()?.takeIf { o.event.dropOff?.personId == input.me && o.start.date == date }
                    ?.let { mission(HandoffKind.DropOff, date, it, kids, o, input, waiting) },
                o.pickUpTime()?.takeIf { o.event.pickUp?.personId == input.me && lastDay(o) == date }
                    ?.let { mission(HandoffKind.PickUp, date, it, kids, o, input, waiting) },
            )
        }.sortedWith(compareBy({ it.time }, { it.kind.ordinal }, { childOrder[it.children.first().id] ?: Int.MAX_VALUE }))
    }

    private fun mission(
        kind: HandoffKind,
        date: LocalDate,
        time: LocalTime,
        kids: List<FamilyPerson>,
        o: EventOccurrence,
        input: HelperInput,
        waiting: List<HandoffRequest>,
    ) = HelperMission(
        kind = kind,
        date = date,
        time = time,
        children = kids,
        occurrence = o,
        title = if (TodayPlanner.isRoutine(o)) null else o.event.title,
        place = placeOf(o, kids, input.children),
        waitingForTakeover = waiting.any { it.kind == kind && it.matches(o) },
    )

    /** The event's location; for school, preschool and fritids the children's school when no location is set. */
    private fun placeOf(o: EventOccurrence, kids: List<FamilyPerson>, children: List<Child>): String? {
        o.event.location.trim().takeIf { it.isNotEmpty() }?.let { return it }
        if (!TodayPlanner.isRoutine(o)) return null
        val schools = kids.mapNotNull { kid -> children.firstOrNull { it.id == kid.id }?.school?.name?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return schools.joinToString(", ").ifEmpty { null }
    }

    /** Today's details: the time state, "Ta med" and "Efteråt". */
    private fun enrich(
        m: HelperMission,
        input: HelperInput,
        packing: Map<String, List<PackingItem>>,
        visible: List<EventOccurrence>,
    ): HelperMission {
        val bring = m.children.flatMap { child ->
            packing[child.id].orEmpty()
                .filter { !it.checked && (m.kind == HandoffKind.DropOff || goesWithPickUp(it, m.time)) }
                .map { HelperBring(it.text.trim(), child) }
        }.distinctBy { it.text.lowercase() to it.child.id }
        return m.copy(
            bring = bring.take(MAX_BRING),
            moreToBring = (bring.size - MAX_BRING).coerceAtLeast(0),
            after = if (m.kind == HandoffKind.PickUp) afterPickUp(m, visible, input.today) else emptyList(),
            state = stateAt(m.time, input.now),
        )
    }

    /** After a pick-up: what the child needs for later events that day, and what moves between homes on a handover day. */
    private fun goesWithPickUp(item: PackingItem, pickUp: LocalTime): Boolean = when (val reason = item.reason) {
        PackingReason.Handover -> true
        is PackingReason.ForEvent -> reason.start != null && reason.start > pickUp
        is PackingReason.Rule -> false
    }

    /** "Efteråt": the children's shared events today that start after the pick-up and before [AFTER_UNTIL]. */
    private fun afterPickUp(m: HelperMission, visible: List<EventOccurrence>, today: LocalDate): List<HelperAfter> = visible
        .filter { o ->
            o.key != m.occurrence.key && o.source != EventSource.Holiday && !o.allDay && o.start.date == today &&
                !TodayPlanner.isRoutine(o) && o.start.time > m.time && o.start.time < AFTER_UNTIL
        }
        .sortedBy { it.start }
        .mapNotNull { o -> m.children.firstOrNull { it.id in o.event.participantIds }?.let { HelperAfter(it, o) } }
        .take(MAX_AFTER)

    /**
     * Requests the caregiver can answer: to him or to anyone, not his own, about something he can see that has not passed.
     * Open ones, and his own answers that can still be changed: a no, or a yes no parent's phone has written in yet. The
     * latest request per occurrence and kind wins.
     */
    private fun requestsFor(
        input: HelperInput,
        requests: List<HandoffRequest>,
        visible: List<EventOccurrence>,
        byId: Map<String, FamilyPerson>,
    ): List<HelperRequest> {
        val me = input.me
        return requests
            .filter { it.fromMemberId != me && (it.toMemberId == null || it.toMemberId == me) }
            .mapNotNull { r ->
                val answer = when {
                    r.status == RequestStatus.Pending -> if (me in r.declines) HelperAnswer.No else null
                    r.status == RequestStatus.Declined && r.respondedBy == me -> HelperAnswer.No
                    r.status == RequestStatus.Accepted && r.respondedBy == me && r.appliedAt == null -> HelperAnswer.Yes
                    else -> return@mapNotNull null
                }
                val o = visible.firstOrNull { r.matches(it) } ?: return@mapNotNull null
                val pickUp = r.kind == HandoffKind.PickUp
                val date = if (pickUp) lastDay(o) else o.start.date
                val time = if (pickUp) o.pickUpTime() else o.dropOffTime()
                if (date < input.today || (date == input.today && time != null && time < input.now)) return@mapNotNull null
                val kids = o.event.participantIds.mapNotNull { byId[it] }.filter { it.isChild }
                HelperRequest(r, byId[r.fromMemberId], kids, date, time, placeOf(o, kids, input.children), o, answer)
            }
            .groupBy { it.occurrence.key to it.kind }
            .values
            .map { same -> same.maxBy { it.request.createdAt ?: Instant.DISTANT_PAST } }
            .sortedWith(compareBy({ it.date }, { it.time }, { it.request.createdAt }))
    }

    /** Today's hand-offs the caregiver said he could not make and someone else took over. */
    private fun takenOver(
        input: HelperInput,
        requests: List<HandoffRequest>,
        visible: List<EventOccurrence>,
        byId: Map<String, FamilyPerson>,
    ): List<HelperTakenOver> = requests
        .filter {
            it.fromMemberId == input.me && it.status == RequestStatus.Accepted && it.respondedBy != null && it.respondedBy != input.me
        }
        .mapNotNull { r ->
            val o = visible.firstOrNull { r.matches(it) } ?: return@mapNotNull null
            val date = if (r.kind == HandoffKind.PickUp) lastDay(o) else o.start.date
            if (date != input.today) return@mapNotNull null
            val kids = o.event.participantIds.mapNotNull { byId[it] }.filter { it.isChild }
            HelperTakenOver(r.kind, r.respondedBy?.let(byId::get), kids, o)
        }
        .distinctBy { it.occurrence.key to it.kind }
        .sortedBy { if (it.kind == HandoffKind.PickUp) it.occurrence.pickUpTime() else it.occurrence.dropOffTime() }

    /** "Ska med": everything for children he drops off, and only handover things for those he picks up. */
    private fun packingFor(
        input: HelperInput,
        packing: Map<String, List<PackingItem>>,
        active: List<HelperMission>,
        byId: Map<String, FamilyPerson>,
    ): List<ChildPacking> {
        val droppedOff = active.filter { it.kind == HandoffKind.DropOff }.flatMap { m -> m.children.map { it.id } }.toSet()
        val pickedUp = active.filter { it.kind == HandoffKind.PickUp }.flatMap { m -> m.children.map { it.id } }.toSet()
        return input.children.mapNotNull { child ->
            val items = packing[child.id].orEmpty().filter { item ->
                child.id in droppedOff || (child.id in pickedUp && item.reason == PackingReason.Handover)
            }
            val person = byId[child.id]
            if (items.isEmpty() || person == null) null else ChildPacking(person, items)
        }
    }

    /**
     * "Att göra": shared, open tasks that are his, or nobody's and due on a day he has a mission. Recurring ones only for
     * today; one-off ones up to [UPCOMING_DAYS] ahead (overdue and undated ones of his too).
     */
    private fun tasksFor(input: HelperInput, shared: List<FamilyTask>, byDay: Map<LocalDate, List<HelperMission>>): List<TaskOccurrence> {
        val today = input.today
        val missionDays = byDay.filterValues { it.isNotEmpty() }.keys
        return shared
            .flatMap { t ->
                if (t.rrule != null) {
                    TaskScheduler.occurrences(t, today, today)
                } else {
                    TaskScheduler.occurrences(t, today.plusDays(-YEAR_DAYS), today.plusDays(UPCOMING_DAYS))
                }
            }
            .filter { o -> !o.done && (o.assigneeId == input.me || (o.assigneeId == null && o.date != null && o.date in missionDays)) }
            .sortedWith(compareBy({ it.date == null }, { it.date }, { it.task.kind != TaskKind.Task }))
            .take(MAX_TASKS)
    }

    private fun firstDayWithMissions(days: List<LocalDate>, byDay: Map<LocalDate, List<HelperMission>>, today: LocalDate): HelperDay? {
        val date = days.firstOrNull { byDay[it].orEmpty().isNotEmpty() } ?: return null
        return HelperDay(date, date.isoWeek.week.takeIf { date.isoWeek != today.isoWeek }, byDay[date].orEmpty())
    }

    /** The week number for each of [dates] where it changes from the date before it (the first compares with today). */
    private fun weekChanges(dates: List<LocalDate>, today: LocalDate): List<Int?> {
        var previous = today.isoWeek
        return dates.map { date ->
            val week = date.isoWeek
            week.week.takeIf { week != previous }.also { previous = week }
        }
    }

    /** "Hela veckan": his missions, everything else shared except the school routines, and red days. Empty days are left out. */
    private fun weekDays(
        days: List<LocalDate>,
        byDay: Map<LocalDate, List<HelperMission>>,
        visible: List<EventOccurrence>,
        byId: Map<String, FamilyPerson>,
        today: LocalDate,
    ): List<HelperWeekDay> {
        val shown = days.mapNotNull { date ->
            val missions = byDay[date].orEmpty()
            val covered = missions.mapTo(HashSet()) { it.occurrence.key }
            val events = visible
                .filter { o -> o.source != EventSource.Holiday && maxOf(o.start.date, today) == date && o.key !in covered }
                .filterNot(TodayPlanner::isRoutine)
                .map { HelperWeekItem.Event(it, it.event.participantIds.mapNotNull(byId::get)) }
            val items = (missions.map(HelperWeekItem::Mission) + events).sortedWith(compareBy({ it.time != null }, { it.time }))
            val holidays = SwedishHolidays.forDate(date)
            if (items.isEmpty() && holidays.isEmpty()) null else HelperWeekDay(date, null, holidays, items)
        }
        return shown.zip(weekChanges(shown.map { it.date }, today)) { day, week -> day.copy(week = week) }
    }

    /** Precipitation or cold at the time of [mission], from the forecast step closest to it (at most three hours away). */
    private fun weatherFor(forecast: Forecast, mission: HelperMission?, input: HelperInput): HelperWeather? {
        mission ?: return null
        val at = LocalDateTime(input.today, mission.time).toInstant(input.zone)
        val point = forecast.points.minByOrNull { (it.time - at).absoluteValue }?.takeIf { (it.time - at).absoluteValue <= 3.hours }
            ?: return null
        val wet = point.precipitationMm >= WET_MM || (point.precipitationProbability ?: 0) >= WET_PROBABILITY
        val condition = when {
            wet && WeatherMath.isFrozen(point) -> HelperWeatherCondition.Snow
            wet -> HelperWeatherCondition.Rain
            point.temperature < COLD_BELOW -> HelperWeatherCondition.Cold
            else -> return null
        }
        return HelperWeather(mission.kind, condition, kotlin.math.round(point.temperature).toInt())
    }

    private fun lastDay(o: EventOccurrence): LocalDate =
        if (o.allDay || o.end.time == LocalTime(0, 0)) maxOf(o.start.date, o.end.date.plusDays(-1)) else o.end.date

    private const val SECONDS_PER_MINUTE = 60
    private const val YEAR_DAYS = 365
    private const val WET_MM = 0.1
    private const val WET_PROBABILITY = 60
}

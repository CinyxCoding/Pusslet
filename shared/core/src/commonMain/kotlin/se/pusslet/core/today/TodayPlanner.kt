package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.conflicts.ConflictDetector
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.custody.Handover
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.meals.MealClash
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Household
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.SickDay
import se.pusslet.core.packing.PackingPlanner
import se.pusslet.core.party.PartyRules
import se.pusslet.core.sick.SickDays
import se.pusslet.core.tasks.KidChores
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.weather.ClothingAdvice
import se.pusslet.core.weather.ClothingAdvisor
import se.pusslet.core.weather.DaySummary
import se.pusslet.core.weather.Forecast
import se.pusslet.core.weather.WeatherMath

/** Everything the Today screen ("Morgonkollen") needs, already decided. */
data class TodayInput(
    val today: LocalDate,
    val now: LocalTime,
    val zone: TimeZone,
    val me: String?,
    val people: List<FamilyPerson>,
    val children: List<Child>,
    /** Occurrences from today through the next few days, all enabled layers. */
    val occurrences: List<EventOccurrence>,
    val packingRules: List<PackingRule> = emptyList(),
    val packingChecks: Map<String, PackingChecks> = emptyMap(),
    val custody: List<CustodySchedule> = emptyList(),
    val mealWeeks: List<MealPlanWeek> = emptyList(),
    val lunches: Map<String, List<SchoolLunch>> = emptyMap(),
    val tasks: List<FamilyTask> = emptyList(),
    val forecast: Forecast? = null,
    val upcomingDays: Int = 3,
    /** From this time the weather and clothing look at tomorrow, so outdoor clothes can be planned the evening before. */
    val tomorrowWeatherFrom: LocalTime = TodayPlanner.TOMORROW_WEATHER_FROM,
    /**
     * A place for the weather is chosen. Without one the weather row is hidden (a caregiver cannot choose one); with one
     * but no forecast yet the row says so ("Inget väder just nu").
     */
    val hasWeatherPlace: Boolean = forecast != null,
    /** The shopping section's data; only read (and passed) while that block is on. */
    val shopping: TodayShopping? = null,
    /** Hand-off requests; a caregiver's open "Jag kan inte" becomes a problem ([TodayPlan.cannotMake]). */
    val requests: List<HandoffRequest> = emptyList(),
    /** Children home sick (ADR-047): no clothes, packing or school lunch for them that day. */
    val sickDays: List<SickDay> = emptyList(),
    /** "Hämtad ✓" (ADR-048): a done pick-up leaves Morgonkollen and is never warned about. */
    val receipts: List<HandoffReceipt> = emptyList(),
    /** Medicine courses and the log (ADR-050): parents are reminded of each planned dose not yet given. */
    val medicineCourses: List<MedicineCourse> = emptyList(),
    val medicineLog: List<MedicineEntry> = emptyList(),
    /** The plans for school breaks (ADR-051): Idag and the booking reminder. */
    val breakPlans: List<BreakPlan> = emptyList(),
) {
    /** The children who go about their day on [date] (the sick ones stay home). */
    fun childrenOutOn(date: LocalDate): List<Child> {
        val home = SickDays.on(sickDays, date).map { it.childId }.toSet()
        return if (home.isEmpty()) children else children.filter { it.id !in home }
    }
}

/**
 * "Bengt kan inte hämta Simon 15:30 · Jag tar det": a caregiver said "Jag kan inte" about a hand-off that is still his, and
 * nobody has taken it over yet (UX plan Steg 3). Today and tomorrow, like the other problems.
 */
data class CaregiverCannot(
    val date: LocalDate,
    val occurrence: EventOccurrence,
    val kind: HandoffKind,
    val time: LocalTime,
    val request: HandoffRequest,
    /** Null when that member has left the family. */
    val from: FamilyPerson?,
    val childIds: List<String>,
)

enum class Greeting { Morning, Day, Evening, Night }

sealed interface TimelineItem {
    val time: LocalTime?

    data class EventItem(val occurrence: EventOccurrence, val people: List<FamilyPerson>) : TimelineItem {
        override val time: LocalTime? get() = if (occurrence.allDay) null else occurrence.start.time
    }

    data class HandoffItem(
        val kind: HandoffKind,
        override val time: LocalTime,
        /** Null = nobody assigned yet. */
        val person: FamilyPerson?,
        val children: List<FamilyPerson>,
        val occurrence: EventOccurrence,
    ) : TimelineItem
}

data class ChildClothing(val child: FamilyPerson, val advice: List<ClothingAdvice>)

data class ChildPacking(val child: FamilyPerson, val items: List<PackingItem>)

data class ChildLunch(val child: FamilyPerson, val dishes: List<String>)

data class CustodyToday(val schedule: CustodySchedule, val household: Household?, val handoverToday: Handover?, val next: Handover?)

/** The old "Kommande" day (with dinner). Kept for the current screen; the block layout uses [UpcomingDay]. */
data class DayPreview(val date: LocalDate, val events: List<EventOccurrence>, val dinner: String?, val holidays: List<Holiday>)

/** The old banners at the top of Idag. Kept for the current screen; the block layout uses [TodayPlan.problems] and [DayReminder]. */
sealed interface ContextCard {
    data class Problem(val conflict: Conflict) : ContextCard

    /** "Studiedag på fredag – vem är hemma?" */
    data class StudyDay(val date: LocalDate, val children: List<FamilyPerson>, val title: String) : ContextCard

    /** "Kalas på lördag – present är inte köpt" */
    data class PartyGift(val date: LocalDate, val eventTitle: String, val taskId: String?) : ContextCard

    /** "Ella har bockat av 2 sysslor – godkänn" (parents only, ADR-055). */
    data class ChoresToApprove(val children: List<FamilyPerson>, val count: Int) : ContextCard

    /** "Dags att OSA till Kalas hos Vera – senast imorgon" (ADR-054). */
    data class PartyRsvp(val by: LocalDate, val occurrence: EventOccurrence) : ContextCard

    /** "Klämdag nästa vecka" */
    data class SqueezeDay(val date: LocalDate) : ContextCard

    data class HandoverSoon(val handover: Handover, val schedule: CustodySchedule) : ContextCard
}

data class TodayPlan(
    val date: LocalDate,
    val week: IsoWeek,
    val greeting: Greeting,
    val holidays: List<Holiday>,
    /** Today's daytime weather, or tomorrow's in the evening (see [weatherForTomorrow]). */
    val weather: DaySummary?,
    val weatherForTomorrow: Boolean,
    val clothing: List<ChildClothing>,
    val custody: List<CustodyToday>,
    val timeline: List<TimelineItem>,
    val packing: List<ChildPacking>,
    val lunches: List<ChildLunch>,
    val dinner: MealEntry?,
    /** Dish keys shared by a child's lunch and tonight's dinner. */
    val lunchDinnerClash: Map<String, Set<String>>,
    val upcoming: List<DayPreview>,
    val myTasks: List<TaskOccurrence>,
    val cards: List<ContextCard>,
    /** The time the plan was built for; the time rules of Morgonkollen were applied with it. */
    val now: LocalTime = LocalTime(0, 0),
    /** Morgonkollen as data (hand-offs, packing, dinner). */
    val hero: TodayHero = TodayHero(),
    /** "Att lösa": conflicts today and tomorrow, by day and time. Never hidden; a hand-off with a problem is only here. */
    val problems: List<Conflict> = emptyList(),
    /** Also "Att lösa": a caregiver's "Jag kan inte" today and tomorrow, by day and time. */
    val cannotMake: List<CaregiverCannot> = emptyList(),
    /** "Kommande": the next [TodayInput.upcomingDays] days that have something, without dinners. */
    val comingUp: List<UpcomingDay> = emptyList(),
    /** The custody part of the header: "Hos Ahmed" or "Byte 15:00 till Ahmed". */
    val custodyNotes: List<CustodyNote> = emptyList(),
    /** False when no weather place is chosen: the weather row is hidden. */
    val hasWeatherPlace: Boolean = weather != null,
    val shopping: TodayShopping? = null,
    /** Kvällskollen: tomorrow made ready, from [TodayInput.tomorrowWeatherFrom]; null earlier or when tomorrow has nothing. */
    val tomorrow: TomorrowPrep? = null,
    /** Somebody else's pick-up today that nobody has ticked off [Pickups.GRACE] after its time (ADR-048). */
    val unconfirmedPickups: List<UnconfirmedPickup> = emptyList(),
    /** Today's ticked-off pick-ups: "Ella hämtad 15:52 · Bengt". */
    val pickedUp: List<PickedUp> = emptyList(),
) {
    val isQuiet: Boolean get() = timeline.isEmpty() && packing.all { it.items.isEmpty() }

    /**
     * "Idag händer" for a layout. One fact once: hand-offs only when the Morgonkollen hand-off row is off, and never
     * one with a problem (that is under "Att lösa").
     */
    fun timelineFor(layout: TodayLayout): List<TimelineItem> {
        val handoffsInHero = layout.isOn(TodayBlock.Handoffs)
        val blocked = TodayPlanner.handoffProblemKeys(problems, date) + TodayPlanner.cannotMakeKeys(cannotMake, date)
        return timeline.filter { item ->
            item !is TimelineItem.HandoffItem || (!handoffsInHero && TodayPlanner.handoffKey(item.occurrence, item.kind) !in blocked)
        }
    }
}

object TodayPlanner {
    /** "Dags att OSA" shows this many days before the last day to answer. */
    private const val RSVP_NOTICE_DAYS = 3

    fun build(input: TodayInput): TodayPlan {
        val today = input.today
        val byId = input.people.associateBy { it.id }
        val out = input.childrenOutOn(today)
        val childPeople = out.map(FamilyPerson::of)
        val todays = input.occurrences.filter { it.overlapsDate(today) }

        val weather = input.forecast?.let { weatherFor(it, input) }
        val clothing = if (weather == null) {
            emptyList()
        } else {
            input.childrenOutOn(weather.date).map { c ->
                ChildClothing(FamilyPerson.of(c), ClothingAdvisor.advise(weather, ClothingAdvisor.ageOn(c.birthDate, weather.date)))
            }
        }

        val timeline = buildTimeline(todays, byId)

        val packingMap = PackingPlanner.itemsFor(today, out, input.packingRules, todays, input.custody, input.packingChecks)
        val packing = childPeople.map { ChildPacking(it, packingMap[it.id].orEmpty()) }

        val dinner = input.mealWeeks.firstOrNull { it.week == today.isoWeek }?.entry(today, MealSlot.Dinner)
        val lunches = childPeople.mapNotNull { c ->
            input.lunches[c.id]?.firstOrNull { it.date == today }?.let { ChildLunch(c, it.dishes) }
        }
        val clash = if (dinner == null) {
            emptyMap()
        } else {
            lunches.associate { it.child.id to MealClash.between(it.dishes, dinner.title) }.filterValues { it.isNotEmpty() }
        }

        val custody = input.custody.map { s ->
            CustodyToday(
                schedule = s,
                household = CustodyCalculator.householdOn(s, today),
                handoverToday = if (CustodyCalculator.isHandoverDay(s, today)) CustodyCalculator.nextHandover(s, today) else null,
                next = CustodyCalculator.nextHandover(s, today.plusDays(1)),
            )
        }

        val upcoming = (1..input.upcomingDays).map { offset ->
            val d = today.plusDays(offset)
            DayPreview(
                date = d,
                events = input.occurrences.filter { it.overlapsDate(d) && !isRoutine(it) },
                dinner = input.mealWeeks.firstOrNull { it.week == d.isoWeek }?.entry(d, MealSlot.Dinner)?.title,
                holidays = SwedishHolidays.forDate(d),
            )
        }

        val myTasks = input.me?.let { TaskScheduler.openFor(it, input.tasks, today, today.plusDays(input.upcomingDays)) }.orEmpty()

        val problems = (0..1).flatMap { offset -> ConflictDetector.detect(today.plusDays(offset), input.occurrences, input.people) }
        val cannotMake = cannotMake(input, byId)
        val handoffsToday = handoffsOn(today, todays, byId)
        val hero = TodayHero(
            handoffs = heroHandoffs(
                input,
                handoffsToday,
                byId,
                handoffProblemKeys(problems, today) + cannotMakeKeys(cannotMake, today),
                from = input.now,
            ),
            packing = packingSummary(today, packing, handoffsToday, todays),
            dinner = TodayDinner(dinner, LunchMerge.merge(lunches, clash), dinnerPlannedRecently(input)),
        )

        return TodayPlan(
            date = today,
            week = today.isoWeek,
            greeting = greeting(input.now),
            holidays = SwedishHolidays.forDate(today),
            weather = weather,
            weatherForTomorrow = weather != null && weather.date != today,
            clothing = clothing,
            custody = custody,
            timeline = timeline,
            packing = packing,
            lunches = lunches,
            dinner = dinner,
            lunchDinnerClash = clash,
            upcoming = upcoming,
            myTasks = myTasks,
            cards = cards(input, input.children.map(FamilyPerson::of), problems),
            now = input.now,
            hero = hero,
            problems = problems.sortedWith(compareBy({ it.date }, { it.sortTime })),
            cannotMake = cannotMake,
            comingUp = comingUp(input, input.children.map(FamilyPerson::of)),
            custodyNotes = custodyNotes(custody, input.now),
            hasWeatherPlace = input.hasWeatherPlace,
            shopping = input.shopping,
            tomorrow = tomorrowPrep(input, byId, problems, cannotMake),
            unconfirmedPickups = Pickups.unconfirmed(today, input.now, input.occurrences, byId, input.receipts)
                .filter { it.pickup.personId != input.me },
            pickedUp = pickedUp(input, byId),
        )
    }

    /** Today's receipts of pick-ups, newest first (ADR-048). */
    private fun pickedUp(input: TodayInput, byId: Map<String, FamilyPerson>): List<PickedUp> = input.receipts
        .filter { it.occurrenceDate == input.today && it.kind == HandoffKind.PickUp }
        .sortedByDescending { it.doneAt }
        .map { r ->
            PickedUp(
                r,
                r.childIds.mapNotNull { byId[it] },
                r.doneBy?.let { byId[it] },
                byMe =
                    r.doneBy != null && r.doneBy == input.me,
            )
        }

    /**
     * Kvällskollen: from [TodayInput.tomorrowWeatherFrom], tomorrow's hand-offs (those with a problem are under "Att lösa"),
     * what to pack and dinner. Null before that time and when tomorrow has nothing of it.
     */
    private fun tomorrowPrep(
        input: TodayInput,
        byId: Map<String, FamilyPerson>,
        problems: List<Conflict>,
        cannotMake: List<CaregiverCannot>,
    ): TomorrowPrep? {
        if (input.now < input.tomorrowWeatherFrom) return null
        val tomorrow = input.today.plusDays(1)
        val tomorrows = input.occurrences.filter { it.overlapsDate(tomorrow) }
        val handoffs = handoffsOn(tomorrow, tomorrows, byId)
        val outTomorrow = input.childrenOutOn(tomorrow)
        val packingMap = PackingPlanner.itemsFor(
            tomorrow,
            outTomorrow,
            input.packingRules,
            tomorrows,
            input.custody,
            input.packingChecks,
        )
        val packing = outTomorrow.map(FamilyPerson::of).map { ChildPacking(it, packingMap[it.id].orEmpty()) }
        val prep = TomorrowPrep(
            date = tomorrow,
            handoffs = heroHandoffs(
                input,
                handoffs,
                byId,
                handoffProblemKeys(problems, tomorrow) + cannotMakeKeys(cannotMake, tomorrow),
                from = null,
            ),
            packing = packingSummary(tomorrow, packing, handoffs, tomorrows),
            dinner = input.mealWeeks.firstOrNull { it.week == tomorrow.isoWeek }?.entry(tomorrow, MealSlot.Dinner),
        )
        return prep.takeUnless { it.isEmpty }
    }

    /** Hidden from 20:00: dinner is over. */
    val DINNER_HIDE_FROM: LocalTime = LocalTime(20, 0)

    /** "Planera middag" is only offered before 18:00; later it is too late to help. */
    val PLAN_DINNER_UNTIL: LocalTime = LocalTime(18, 0)

    /** Identifies one hand-off of one occurrence. */
    internal fun handoffKey(occurrence: EventOccurrence, kind: HandoffKind): String = "${occurrence.key}/${kind.name}"

    /** The hand-offs on [date] that have a problem (nobody, nobody available, the assignee is busy). */
    internal fun handoffProblemKeys(problems: List<Conflict>, date: LocalDate): Set<String> = problems
        .filter { it.date == date }
        .mapNotNullTo(HashSet()) { c ->
            when (c) {
                is Conflict.Unassigned -> handoffKey(c.occurrence, c.kind)
                is Conflict.NobodyAvailable -> handoffKey(c.occurrence, c.kind)
                is Conflict.AssigneeBusy -> handoffKey(c.occurrence, c.kind)
                is Conflict.DoubleBooked -> null
            }
        }

    /** The hand-offs on [date] a caregiver said he cannot make. */
    internal fun cannotMakeKeys(cannot: List<CaregiverCannot>, date: LocalDate): Set<String> =
        cannot.filter { it.date == date }.mapTo(HashSet()) { handoffKey(it.occurrence, it.kind) }

    /**
     * A caregiver's open "Jag kan inte" (to anyone, from a caregiver) about a family hand-off today or tomorrow that is still
     * his and has not passed. Once a parent takes it (the request is answered) or gives it to someone else, it is solved.
     */
    private fun cannotMake(input: TodayInput, byId: Map<String, FamilyPerson>): List<CaregiverCannot> {
        val tomorrow = input.today.plusDays(1)
        return input.requests
            .filter { it.status == RequestStatus.Pending && it.toMemberId == null && byId[it.fromMemberId]?.role == Role.Caregiver }
            .mapNotNull { r ->
                val o = input.occurrences.firstOrNull { it.source == EventSource.Family && r.matches(it) } ?: return@mapNotNull null
                val pickUp = r.kind == HandoffKind.PickUp
                val handoff = if (pickUp) o.event.pickUp else o.event.dropOff
                if (handoff?.personId != r.fromMemberId) return@mapNotNull null
                val time = (if (pickUp) o.pickUpTime() else o.dropOffTime()) ?: return@mapNotNull null
                val date = if (pickUp && !o.allDay) o.end.date else o.start.date
                if (date < input.today || date > tomorrow || (date == input.today && time < input.now)) return@mapNotNull null
                val children = o.attending.filter { byId[it]?.isChild == true }
                CaregiverCannot(date, o, r.kind, time, r, byId[r.fromMemberId], children)
            }
            .distinctBy { it.occurrence.key to it.kind }
            .sortedWith(compareBy({ it.date }, { it.time }))
    }

    /** A drop-off or pick-up on a given day, with a time and at least one child. */
    private class DayHandoff(
        val kind: HandoffKind,
        val personId: String?,
        val time: LocalTime,
        val occurrence: EventOccurrence,
        val children: List<FamilyPerson>,
    )

    /** Drop-offs on the first day of an occurrence and pick-ups on its last, so a trip over several days counts once. */
    private fun handoffsOn(date: LocalDate, todays: List<EventOccurrence>, byId: Map<String, FamilyPerson>): List<DayHandoff> =
        todays.flatMap { o ->
            val kids = o.attending.mapNotNull { byId[it] }.filter { it.isChild }
            if (kids.isEmpty()) return@flatMap emptyList()
            listOfNotNull(
                o.event.dropOff?.takeIf { o.start.date == date }?.let { h ->
                    o.dropOffTime()?.let { DayHandoff(HandoffKind.DropOff, h.personId, it, o, kids) }
                },
                o.event.pickUp?.takeIf { lastDay(o) == date }?.let { h ->
                    o.pickUpTime()?.let { DayHandoff(HandoffKind.PickUp, h.personId, it, o, kids) }
                },
            )
        }

    private fun lastDay(o: EventOccurrence): LocalDate =
        if (o.allDay || o.end.time == LocalTime(0, 0)) maxOf(o.start.date, o.end.date.plusDays(-1)) else o.end.date

    /**
     * "Johan lämnar Simon 07:50 och Ella 08:05": one line per kind and person, sorted by the first time. Hand-offs before
     * [from] (passed today; null = keep all), with nobody or with a problem are left out (the problem is under "Att lösa").
     */
    private fun heroHandoffs(
        input: TodayInput,
        handoffs: List<DayHandoff>,
        byId: Map<String, FamilyPerson>,
        blocked: Set<String>,
        from: LocalTime?,
    ): List<HandoffLine> {
        val childOrder = input.children.withIndex().associate { (i, c) -> c.id to i }

        // My own pick-up stays after its time until it is ticked off ("Hämtad", ADR-048); a done one is gone.
        fun mineToConfirm(h: DayHandoff) = from != null && h.kind == HandoffKind.PickUp && h.personId == input.me
        return handoffs
            .filter {
                (from == null || it.time >= from || mineToConfirm(it)) && it.personId != null &&
                    handoffKey(it.occurrence, it.kind) !in blocked
            }
            .filter { Pickups.receiptFor(input.receipts, it.occurrence, it.kind) == null }
            .groupBy { it.kind to it.personId }
            .map { (key, list) ->
                val (kind, personId) = key
                val stops = list.flatMap { h -> h.children.map { HandoffStop(it, h.time, h.occurrence) } }
                    .sortedWith(compareBy({ it.time }, { childOrder[it.child.id] ?: Int.MAX_VALUE }))
                val isMe = personId != null && personId == input.me
                val confirmable = from != null && isMe && kind == HandoffKind.PickUp && Pickups.confirmable(stops.first().time, from)
                HandoffLine(kind, personId?.let(byId::get), isMe = isMe, stops = stops, confirmable = confirmable)
            }
            .sortedWith(compareBy({ it.firstTime }, { it.kind.ordinal }))
    }

    /** Only children with something to bring; "Allt är packat" lasts until the last of them has left. */
    private fun packingSummary(
        date: LocalDate,
        packing: List<ChildPacking>,
        handoffs: List<DayHandoff>,
        todays: List<EventOccurrence>,
    ): TodayPacking {
        val children = packing.filter { it.items.isNotEmpty() }
        val ids = children.mapTo(HashSet()) { it.child.id }
        val lastDropOff = handoffs.filter { h -> h.kind == HandoffKind.DropOff && h.children.any { it.id in ids } }.maxOfOrNull { it.time }
        val leavesAt = lastDropOff ?: todays
            .filter { o -> !o.allDay && o.start.date == date && o.attending.any { it in ids } }
            .maxOfOrNull { it.start.time }
        return TodayPacking(children, leavesAt)
    }

    /** A prompt to plan dinner is only welcome in a family that plans dinners: one this or last ISO week. */
    private fun dinnerPlannedRecently(input: TodayInput): Boolean {
        val weeks = setOf(input.today.isoWeek, input.today.isoWeek.previous())
        return input.mealWeeks.any { w -> w.week in weeks && w.entries.values.any { it.slot == MealSlot.Dinner } }
    }

    /**
     * The next days with something on them: events that are not routine blocks (each once, on its first day; what already
     * runs today is in today's timeline), red days and reminders. Parties and study days are carried by their reminder.
     */
    private fun comingUp(input: TodayInput, children: List<FamilyPerson>): List<UpcomingDay> {
        if (input.upcomingDays < 1) return emptyList()
        val today = input.today
        val window = today.plusDays(1)..today.plusDays(input.upcomingDays)
        val starting = input.occurrences.filter { it.start.date in window && !isRoutine(it) }
        return (1..input.upcomingDays).map { offset ->
            val d = today.plusDays(offset)
            val events = starting.filter { it.start.date == d }.sortedWith(compareBy({ !it.allDay }, { it.start }))
            val reminders = ArrayList<DayReminder>()
            val carried = HashSet<String>()
            for (o in events) {
                if (PartyRules.isParty(o.event)) {
                    // A party whose present is bought, or that the family said no to or holds itself, is just an event.
                    if (PartyRules.needsGift(o, input.tasks, today)) {
                        reminders += DayReminder.PartyGift(o, PartyRules.giftTasks(o, input.tasks, today).firstOrNull()?.id)
                        carried += o.key
                    }
                } else if (o.hasTag(EventTags.STUDY_DAY)) {
                    reminders += DayReminder.StudyDay(o, children.filter { it.id in o.attending })
                    carried += o.key
                }
            }
            if (SwedishHolidays.isSqueezeDay(d)) reminders += DayReminder.SqueezeDay
            UpcomingDay(d, events.filter { it.key !in carried }, SwedishHolidays.forDate(d), reminders)
        }.filterNot { it.isEmpty }
    }

    /** "Hos Ahmed", or "Byte 15:00 till Ahmed" on the changeover day until it has happened. */
    private fun custodyNotes(custody: List<CustodyToday>, now: LocalTime): List<CustodyNote> = custody.mapNotNull { c ->
        val handover = c.handoverToday
        if (handover != null && now <= handover.time) {
            CustodyNote.Changeover(c.schedule, handover)
        } else {
            c.household?.let { CustodyNote.With(c.schedule, it) }
        }
    }

    private fun EventOccurrence.hasTag(tag: String): Boolean = event.tags.any { it.equals(tag, ignoreCase = true) }

    private val Conflict.sortTime: LocalTime
        get() = when (this) {
            is Conflict.Unassigned -> time
            is Conflict.NobodyAvailable -> time
            is Conflict.AssigneeBusy -> time
            is Conflict.DoubleBooked -> occurrence.start.time
        }

    /**
     * In the evening the day's weather is mostly over, so tomorrow's daytime forecast is what helps ("vilka ytterkläder?").
     * Falls back to today when the forecast does not reach tomorrow.
     */
    private fun weatherFor(forecast: Forecast, input: TodayInput): DaySummary? {
        val today = input.today
        val todays = { WeatherMath.summarize(forecast, today, input.zone, now = LocalDateTime(today, input.now).toInstant(input.zone)) }
        if (input.now < input.tomorrowWeatherFrom) return todays()
        return WeatherMath.summarize(forecast, today.plusDays(1), input.zone) ?: todays()
    }

    /** School/preschool blocks are shown as their drop-off and pick-up, not as a big event. */
    fun isRoutine(o: EventOccurrence): Boolean = isRoutineEvent(o.event)

    /** [isRoutine] for an event, whatever occurrence: tagged school, preschool or after-school. */
    fun isRoutineEvent(event: Event): Boolean =
        event.tags.any { it.equals("skola", true) || it.equals("förskola", true) || it.equals("fritids", true) }

    private fun buildTimeline(todays: List<EventOccurrence>, byId: Map<String, FamilyPerson>): List<TimelineItem> {
        val items = ArrayList<TimelineItem>()
        for (o in todays) {
            val kids = o.attending.mapNotNull { byId[it] }.filter { it.isChild }
            o.event.dropOff?.let { h ->
                o.dropOffTime()?.let { t -> items += TimelineItem.HandoffItem(HandoffKind.DropOff, t, h.personId?.let(byId::get), kids, o) }
            }
            if (!isRoutine(o)) items += TimelineItem.EventItem(o, o.attending.mapNotNull { byId[it] })
            o.event.pickUp?.let { h ->
                o.pickUpTime()?.let { t -> items += TimelineItem.HandoffItem(HandoffKind.PickUp, t, h.personId?.let(byId::get), kids, o) }
            }
        }
        return items.sortedWith(compareBy({ it.time != null }, { it.time }))
    }

    private fun cards(input: TodayInput, children: List<FamilyPerson>, problems: List<Conflict>): List<ContextCard> {
        val today = input.today
        val cards = ArrayList<ContextCard>()
        val horizon = today.plusDays(7)

        // Conflicts today and tomorrow.
        problems.forEach { cards += ContextCard.Problem(it) }

        // Study days and parties in the coming week.
        val week = input.occurrences.filter { it.start.date in today.plusDays(1)..horizon }
        week.filter { o -> o.event.tags.any { it.equals(EventTags.STUDY_DAY, true) } }
            .distinctBy { it.start.date }
            .forEach { o ->
                cards += ContextCard.StudyDay(o.start.date, children.filter { it.id in o.attending }, o.event.title)
            }
        week.filter { PartyRules.needsGift(it, input.tasks, today) }.forEach { o ->
            cards += ContextCard.PartyGift(o.start.date, o.event.title, PartyRules.giftTasks(o, input.tasks, today).firstOrNull()?.id)
        }
        // Children's chores waiting for a parent (ADR-055).
        val pending = KidChores.pending(input.tasks, children.map { it.id }.toSet(), input.zone)
        if (pending.isNotEmpty()) {
            cards += ContextCard.ChoresToApprove(children.filter { c -> pending.any { it.childId == c.id } }, pending.size)
        }
        // An answer due within three days, whenever the party is.
        input.occurrences.filter { PartyRules.needsRsvp(it.event, today) }
            .distinctBy { it.event.seriesId ?: it.event.id }
            .forEach { o ->
                val by = PartyRules.details(o.event).rsvpBy ?: return@forEach
                if (by <= today.plusDays(RSVP_NOTICE_DAYS)) cards += ContextCard.PartyRsvp(by, o)
            }

        // Squeeze days within ten days.
        (1..10).map { today.plusDays(it) }.firstOrNull { SwedishHolidays.isSqueezeDay(it) }?.let { cards += ContextCard.SqueezeDay(it) }

        // Custody handover today or tomorrow.
        input.custody.forEach { s ->
            CustodyCalculator.nextHandover(s, today)?.takeIf { it.date <= today.plusDays(1) }?.let {
                cards +=
                    ContextCard.HandoverSoon(it, s)
            }
        }
        return cards
    }

    val TOMORROW_WEATHER_FROM: LocalTime = LocalTime(18, 0)

    fun greeting(now: LocalTime): Greeting = when (now.hour) {
        in 5..9 -> Greeting.Morning
        in 10..16 -> Greeting.Day
        in 17..22 -> Greeting.Evening
        else -> Greeting.Night
    }
}

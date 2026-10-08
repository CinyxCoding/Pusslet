package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.conflicts.ConflictDetector
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.custody.Handover
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.meals.MealClash
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Household
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingItem
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.packing.PackingPlanner
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

data class DayPreview(val date: LocalDate, val events: List<EventOccurrence>, val dinner: String?, val holidays: List<Holiday>)

sealed interface ContextCard {
    data class Problem(val conflict: Conflict) : ContextCard

    /** "Studiedag på fredag – vem är hemma?" */
    data class StudyDay(val date: LocalDate, val children: List<FamilyPerson>, val title: String) : ContextCard

    /** "Kalas på lördag – present är inte köpt" */
    data class PartyGift(val date: LocalDate, val eventTitle: String, val taskId: String?) : ContextCard

    /** "Klämdag nästa vecka" */
    data class SqueezeDay(val date: LocalDate) : ContextCard

    data class HandoverSoon(val handover: Handover, val schedule: CustodySchedule) : ContextCard
}

data class TodayPlan(
    val date: LocalDate,
    val week: IsoWeek,
    val greeting: Greeting,
    val holidays: List<Holiday>,
    val weather: DaySummary?,
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
) {
    val isQuiet: Boolean get() = timeline.isEmpty() && packing.all { it.items.isEmpty() }
}

object TodayPlanner {
    fun build(input: TodayInput): TodayPlan {
        val today = input.today
        val byId = input.people.associateBy { it.id }
        val childPeople = input.children.map(FamilyPerson::of)
        val todays = input.occurrences.filter { it.overlapsDate(today) }

        val weather = input.forecast?.let { WeatherMath.summarize(it, today, input.zone) }
        val clothing = if (weather == null) {
            emptyList()
        } else {
            input.children.map { c -> ChildClothing(FamilyPerson.of(c), ClothingAdvisor.advise(weather, ClothingAdvisor.ageOn(c.birthDate, today))) }
        }

        val timeline = buildTimeline(todays, byId)

        val packingMap = PackingPlanner.itemsFor(today, input.children, input.packingRules, todays, input.custody, input.packingChecks)
        val packing = childPeople.map { ChildPacking(it, packingMap[it.id].orEmpty()) }

        val dinner = input.mealWeeks.firstOrNull { it.week == today.isoWeek }?.entry(today, MealSlot.Dinner)
        val lunches = childPeople.mapNotNull { c ->
            input.lunches[c.id]?.firstOrNull { it.date == today }?.let { ChildLunch(c, it.dishes) }
        }
        val clash = if (dinner == null) emptyMap() else lunches.associate { it.child.id to MealClash.between(it.dishes, dinner.title) }.filterValues { it.isNotEmpty() }

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

        return TodayPlan(
            date = today,
            week = today.isoWeek,
            greeting = greeting(input.now),
            holidays = SwedishHolidays.forDate(today),
            weather = weather,
            clothing = clothing,
            custody = custody,
            timeline = timeline,
            packing = packing,
            lunches = lunches,
            dinner = dinner,
            lunchDinnerClash = clash,
            upcoming = upcoming,
            myTasks = myTasks,
            cards = cards(input, childPeople),
        )
    }

    /** School/preschool blocks are shown as their drop-off and pick-up, not as a big event. */
    fun isRoutine(o: EventOccurrence): Boolean =
        o.event.tags.any { it.equals("skola", true) || it.equals("förskola", true) || it.equals("fritids", true) }

    private fun buildTimeline(todays: List<EventOccurrence>, byId: Map<String, FamilyPerson>): List<TimelineItem> {
        val items = ArrayList<TimelineItem>()
        for (o in todays) {
            val kids = o.event.participantIds.mapNotNull { byId[it] }.filter { it.isChild }
            o.event.dropOff?.let { h ->
                o.dropOffTime()?.let { t -> items += TimelineItem.HandoffItem(HandoffKind.DropOff, t, h.personId?.let(byId::get), kids, o) }
            }
            if (!isRoutine(o)) items += TimelineItem.EventItem(o, o.event.participantIds.mapNotNull { byId[it] })
            o.event.pickUp?.let { h ->
                o.pickUpTime()?.let { t -> items += TimelineItem.HandoffItem(HandoffKind.PickUp, t, h.personId?.let(byId::get), kids, o) }
            }
        }
        return items.sortedWith(compareBy({ it.time != null }, { it.time }))
    }

    private fun cards(input: TodayInput, children: List<FamilyPerson>): List<ContextCard> {
        val today = input.today
        val cards = ArrayList<ContextCard>()
        val horizon = today.plusDays(7)

        // Conflicts today and tomorrow.
        for (offset in 0..1) {
            val d = today.plusDays(offset)
            ConflictDetector.detect(d, input.occurrences, input.people).forEach { cards += ContextCard.Problem(it) }
        }

        // Study days and parties in the coming week.
        val week = input.occurrences.filter { it.start.date in today.plusDays(1)..horizon }
        week.filter { o -> o.event.tags.any { it.equals(EventTags.STUDY_DAY, true) } }
            .distinctBy { it.start.date }
            .forEach { o -> cards += ContextCard.StudyDay(o.start.date, children.filter { it.id in o.event.participantIds }, o.event.title) }
        week.filter { o -> o.event.tags.any { it.equals(EventTags.PARTY, true) } }.forEach { o ->
            val giftTasks = input.tasks.filter { t ->
                t.linkedEventId == o.event.id || (t.category == TaskCategory.Gift && t.due != null && t.due <= o.start.date && t.due >= today)
            }
            if (giftTasks.none { it.done }) cards += ContextCard.PartyGift(o.start.date, o.event.title, giftTasks.firstOrNull()?.id)
        }

        // Squeeze days within ten days.
        (1..10).map { today.plusDays(it) }.firstOrNull { SwedishHolidays.isSqueezeDay(it) }?.let { cards += ContextCard.SqueezeDay(it) }

        // Custody handover today or tomorrow.
        input.custody.forEach { s ->
            CustodyCalculator.nextHandover(s, today)?.takeIf { it.date <= today.plusDays(1) }?.let { cards += ContextCard.HandoverSoon(it, s) }
        }
        return cards
    }

    fun greeting(now: LocalTime): Greeting = when (now.hour) {
        in 5..9 -> Greeting.Morning
        in 10..16 -> Greeting.Day
        in 17..22 -> Greeting.Evening
        else -> Greeting.Night
    }
}

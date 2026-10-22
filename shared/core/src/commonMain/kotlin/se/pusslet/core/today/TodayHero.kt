package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.custody.Handover
import se.pusslet.core.holidays.Holiday
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Household
import se.pusslet.core.model.MealEntry

/**
 * Morgonkollen as data, not strings (UX plan, Idag): the hand-offs, what to bring and dinner. The weather row uses
 * [TodayPlan.weather], [TodayPlan.weatherForTomorrow] and [TodayPlan.clothing].
 */
data class TodayHero(
    /** Sorted by first time. Passed hand-offs and those with a problem (shown under "Att lösa") are left out. */
    val handoffs: List<HandoffLine> = emptyList(),
    val packing: TodayPacking = TodayPacking(),
    val dinner: TodayDinner = TodayDinner(),
)

/**
 * One sentence per person and kind: "Johan lämnar Simon 07:50 och Ella 08:05", or "Du hämtar Simon 15:30" when
 * [isMe] (shown in bold). A tap opens the stop's occurrence.
 */
data class HandoffLine(
    val kind: HandoffKind,
    /** Who does it. Null only when the assignee is no longer in the family ("Inte bestämt"). */
    val person: FamilyPerson?,
    val isMe: Boolean,
    /** Never empty, sorted by time. */
    val stops: List<HandoffStop>,
    /** My own pick-up from [Pickups.CONFIRM_FROM] before its time: "Hämtad" is offered (ADR-048). */
    val confirmable: Boolean = false,
) {
    val firstTime: LocalTime get() = stops.first().time
}

/** One child in a [HandoffLine]: "Simon 07:50". */
data class HandoffStop(val child: FamilyPerson, val time: LocalTime, val occurrence: EventOccurrence)

/** What the packing row says. */
enum class PackingState {
    /** Nothing to bring today: no row. */
    Nothing,

    /** "Ska med: gympapåse (Ella)". */
    ToPack,

    /** "Allt är packat", until the children have left (see [TodayPacking.leavesAt]). */
    AllPacked,
}

/** "Ska med" for today: one row that unfolds to check boxes per child. */
data class TodayPacking(
    /** Only children with something to bring, in family order. */
    val children: List<ChildPacking> = emptyList(),
    /**
     * When the last of these children leaves: their last drop-off today, else the start of their last timed event.
     * "Allt är packat" is shown until then; null = all day.
     */
    val leavesAt: LocalTime? = null,
) {
    val total: Int get() = children.sumOf { it.items.size }
    val packed: Int get() = children.sumOf { child -> child.items.count { it.checked } }
    val state: PackingState
        get() = when {
            total == 0 -> PackingState.Nothing
            packed < total -> PackingState.ToPack
            else -> PackingState.AllPacked
        }
    val allPacked: Boolean get() = state == PackingState.AllPacked

    /** Something to pack is shown all day; "Allt är packat" until [leavesAt] has passed. */
    fun isShownAt(now: LocalTime): Boolean = when (state) {
        PackingState.Nothing -> false
        PackingState.ToPack -> true
        PackingState.AllPacked -> leavesAt == null || now <= leavesAt
    }
}

/**
 * Kvällskollen (UX plan, Idag): from [TodayInput.tomorrowWeatherFrom] Idag also gets tomorrow ready – who leaves and picks
 * up, what to pack tonight and tomorrow's dinner. Tomorrow's weather and clothing are already [TodayPlan.weather]. Problems
 * for tomorrow stay under "Att lösa"; events stay in "Kommande".
 */
data class TomorrowPrep(
    val date: LocalDate,
    /** Tomorrow's hand-off lines, without those that have a problem. */
    val handoffs: List<HandoffLine> = emptyList(),
    /** What to pack for tomorrow; checking it tonight is the point ("Packa ikväll: gympapåse (Ella)"). */
    val packing: TodayPacking = TodayPacking(),
    val dinner: MealEntry? = null,
) {
    val isEmpty: Boolean get() = handoffs.isEmpty() && packing.total == 0 && dinner == null
}

/** Children who eat the same school lunch, one row: "Lunch: Köttbullar (Ella, Simon)". */
data class LunchGroup(
    val children: List<FamilyPerson>,
    /** The main dish as the first child's menu spells it. */
    val dish: String,
    /** Dish words this lunch shares with tonight's dinner ("Ella och Simon åt fisk till lunch"); empty = no clash. */
    val clash: Set<String> = emptySet(),
)

/** "Ikväll: Fiskgratäng" with at most one line under it, or the "Planera middag" prompt. */
data class TodayDinner(
    val dinner: MealEntry? = null,
    val lunches: List<LunchGroup> = emptyList(),
    /** The family planned at least one dinner this or last ISO week, so a prompt is welcome rather than nagging. */
    val dinnerPlannedRecently: Boolean = false,
) {
    /** The lunches that share a dish with dinner. The clash wins the one line under dinner. */
    val clashes: List<LunchGroup> get() = lunches.filter { it.clash.isNotEmpty() }

    /** "Planera middag": no dinner yet, before 18:00 and the family plans dinners. */
    fun planPromptAt(now: LocalTime): Boolean = dinner == null && dinnerPlannedRecently && now < TodayPlanner.PLAN_DINNER_UNTIL

    /** Dinner until 20:00; without one, the prompt. */
    fun isShownAt(now: LocalTime): Boolean = if (dinner != null) now < TodayPlanner.DINNER_HIDE_FROM else planPromptAt(now)
}

/** Groups school lunches by main dish (the first one, which the school serves to most). */
object LunchMerge {
    /**
     * Children whose first dish is the same (ignoring case and surrounding spaces) share one group. Groups keep the order
     * of their first child and children keep their order; lunches without any dish are left out.
     */
    fun merge(lunches: List<ChildLunch>, clash: Map<String, Set<String>> = emptyMap()): List<LunchGroup> = lunches
        .filter { it.dishes.any(String::isNotBlank) }
        .groupBy { lunch -> mainDish(lunch.dishes).lowercase() }
        .values
        .map { same ->
            LunchGroup(
                children = same.map { it.child },
                dish = mainDish(same.first().dishes),
                clash = same.flatMapTo(LinkedHashSet()) { clash[it.child.id].orEmpty() },
            )
        }

    private fun mainDish(dishes: List<String>): String = dishes.first(String::isNotBlank).trim()
}

/** A day in "Kommande": events that are not routine, red days and reminders. Days with nothing are left out. */
data class UpcomingDay(
    val date: LocalDate,
    /** All-day first, then by start. Each occurrence shows once, on its first day; those carried by a reminder are left out. */
    val events: List<EventOccurrence>,
    val holidays: List<Holiday>,
    val reminders: List<DayReminder>,
) {
    val isEmpty: Boolean get() = events.isEmpty() && holidays.isEmpty() && reminders.isEmpty()
}

/** Something to act on, on its day in "Kommande" (they used to be banners at the top of Idag). */
sealed interface DayReminder {
    /** "14:00 Kalas hos Vera – present?". [taskId] is the open gift to-do; null = none yet, so "Påminn mig" can add one. */
    data class PartyGift(val occurrence: EventOccurrence, val taskId: String?) : DayReminder

    /** "Studiedag – vem är hemma?" */
    data class StudyDay(val occurrence: EventOccurrence, val children: List<FamilyPerson>) : DayReminder

    /** "Klämdag": a working day between a red day and the weekend. */
    data object SqueezeDay : DayReminder
}

/** The custody part of the header (växelvis boende); replaces the old handover card. */
sealed interface CustodyNote {
    val schedule: CustodySchedule

    /** "Hos Ahmed": where the children are today (after a changeover: the new home). */
    data class With(override val schedule: CustodySchedule, val household: Household) : CustodyNote

    /** "Byte 15:00 till Ahmed": the changeover is today and has not happened yet. */
    data class Changeover(override val schedule: CustodySchedule, val handover: Handover) : CustodyNote
}

/** The shopping section: "Veckohandling: 5 kvar" or "Sara handlar nu". Only read when the block is on. */
data class TodayShopping(
    val listId: String,
    val listName: String,
    /** Unchecked items on the list. */
    val remaining: Int,
    /** Who is in the shop right now. */
    val shoppers: List<FamilyPerson> = emptyList(),
) {
    val hasContent: Boolean get() = remaining > 0 || shoppers.isNotEmpty()
}

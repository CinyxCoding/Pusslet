package se.pusslet.feature.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.today.HandoffLine
import se.pusslet.core.today.LunchGroup
import se.pusslet.core.today.Placement
import se.pusslet.core.today.TimelineItem
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayout
import se.pusslet.core.today.TodayLayouts
import se.pusslet.core.today.TodayPacking
import se.pusslet.core.today.TodayPlan
import se.pusslet.core.today.TodayShopping
import kotlin.time.Instant

/**
 * One row of Morgonkollen (UX plan, Idag), already decided. The rows come in the fixed order hand-offs, packing, dinner,
 * weather and there are at most [TodayModel.MAX_HERO_ROWS] of them; the card has no visible title.
 */
sealed interface HeroRow {
    /** "Johan lämnar Simon 07:50 och Ella 08:05"; the viewer's own rows ("Du hämtar …") are emphasized. Opens the event. */
    data class Handoff(val line: HandoffLine) : HeroRow

    /**
     * "Ska med: gympapåse (Ella)" or "Allt är packat"; unfolds in place to check boxes per child. [date] is tomorrow in
     * Kvällskollen ("Packa ikväll: …"); null = the plan's day.
     */
    data class Packing(val packing: TodayPacking, val date: LocalDate? = null) : HeroRow

    /**
     * "Ikväll: Fiskgratäng" with at most one line under it: a clash with a school lunch, else the merged lunch. [tomorrow] =
     * "Middag: Tacos" in Kvällskollen.
     */
    data class Dinner(val dinner: MealEntry, val lunches: List<LunchGroup>, val tomorrow: Boolean = false) : HeroRow

    /** "Planera middag": parents only, no dinner yet, before 18:00 and the family planned a dinner this or last week. */
    data object PlanDinner : HeroRow

    /** "☁ 14° · Ella och Simon: tunn jacka" (tomorrow's from 18:00), drawn from the plan and [TodayUiState.weather]. */
    data object Weather : HeroRow

    /** The weather row is on but no place is chosen: "Välj ort" opens the settings. Parents only; others get no row. */
    data object ChoosePlace : HeroRow
}

/**
 * An unanswered hand-off request to the viewer, under "Att lösa": "Sara frågar om du kan hämta Ella fre 15:45 · Svara".
 * "Svara" opens [occurrence], where the request is answered.
 */
data class TodayRequest(
    val request: HandoffRequest,
    /** Who asks; null when that member has left the family. */
    val from: FamilyPerson?,
    val children: List<FamilyPerson>,
    /** The day of the hand-off: drop-offs on the first day, pick-ups on the last. */
    val date: LocalDate,
    val time: LocalTime?,
    val occurrence: EventOccurrence,
) {
    val kind: HandoffKind get() = request.kind
}

/** The shopping list the "Inköpslistan" section reads, before the people and the clock are applied. */
internal data class ShoppingSnapshot(val list: ShoppingList, val remaining: Int, val presence: List<ShoppingPresence>)

/** The rules that turn a [TodayPlan] and a [TodayLayout] into what Idag draws. Pure, so the view model tests cover them. */
internal object TodayModel {
    const val MAX_HERO_ROWS = 5
    const val MAX_PROBLEMS = 3
    const val MAX_TIMELINE_ROWS = 5
    const val MAX_TASKS = 3

    /**
     * Morgonkollen: the hero blocks that are switched on and have something to show, in their fixed order. Hand-off lines
     * give way to the other rows when there would be more than [MAX_HERO_ROWS]. While the forecast is loading the weather
     * row waits, so it does not flash "Inget väder just nu".
     */
    fun heroRows(layout: TodayLayout, plan: TodayPlan, canEdit: Boolean, weatherLoading: Boolean): List<HeroRow> {
        val visible = TodayLayouts.visibleBlocks(layout, plan).filter { it.placement == Placement.Hero }.toSet()
        val others = buildList {
            if (TodayBlock.Packing in visible) add(HeroRow.Packing(plan.hero.packing))
            if (TodayBlock.Dinner in visible) {
                val dinner = plan.hero.dinner
                val entry = dinner.dinner
                when {
                    entry != null -> add(HeroRow.Dinner(entry, dinner.lunches))
                    canEdit && dinner.planPromptAt(plan.now) -> add(HeroRow.PlanDinner)
                }
            }
            // In the evening tomorrow's weather belongs to Kvällskollen (see tomorrowRows).
            if (TodayBlock.Weather in visible && !weatherInKvallskoll(layout, plan)) {
                if (!weatherLoading) add(HeroRow.Weather)
            } else if (canEdit && layout.isOn(TodayBlock.Weather) && !plan.hasWeatherPlace) {
                add(HeroRow.ChoosePlace)
            }
        }
        val handoffs = if (TodayBlock.Handoffs in visible) {
            plan.hero.handoffs.filter { line -> line.stops.any { it.time >= plan.now } }
        } else {
            emptyList()
        }
        val room = (MAX_HERO_ROWS - others.size).coerceAtLeast(0)
        return handoffs.take(room).map(HeroRow::Handoff) + others
    }

    /**
     * Kvällskollen ("Imorgon, onsdag"), from 18:00: tomorrow's hand-offs, what to pack tonight, tomorrow's weather and dinner,
     * for the blocks that are switched on. Empty when the plan has no [TodayPlan.tomorrow].
     */
    fun tomorrowRows(layout: TodayLayout, plan: TodayPlan, weatherLoading: Boolean): List<HeroRow> {
        val prep = plan.tomorrow ?: return emptyList()
        val others = buildList {
            if (layout.isOn(TodayBlock.Packing) && prep.packing.total > 0) add(HeroRow.Packing(prep.packing, prep.date))
            val dinner = prep.dinner
            if (layout.isOn(TodayBlock.Dinner) && dinner != null) add(HeroRow.Dinner(dinner, emptyList(), tomorrow = true))
            if (weatherInKvallskoll(layout, plan) && !weatherLoading) add(HeroRow.Weather)
        }
        val handoffs = if (layout.isOn(TodayBlock.Handoffs)) prep.handoffs else emptyList()
        val room = (MAX_HERO_ROWS - others.size).coerceAtLeast(0)
        val rows = handoffs.take(room).map(HeroRow::Handoff) + others
        // Weather alone is not a reason for the card: Morgonkollen keeps it then.
        return rows.takeUnless { it.all { row -> row == HeroRow.Weather } }.orEmpty()
    }

    /** Tomorrow's weather moves into Kvällskollen when there is one with something else in it. */
    private fun weatherInKvallskoll(layout: TodayLayout, plan: TodayPlan): Boolean {
        val prep = plan.tomorrow ?: return false
        val other = (layout.isOn(TodayBlock.Handoffs) && prep.handoffs.isNotEmpty()) ||
            (layout.isOn(TodayBlock.Packing) && prep.packing.total > 0) ||
            (layout.isOn(TodayBlock.Dinner) && prep.dinner != null)
        return other && plan.weatherForTomorrow && layout.isOn(TodayBlock.Weather)
    }

    /** The sections to draw, in the user's order: switched on and with something to show ("tomt är tyst"). */
    fun sections(layout: TodayLayout, plan: TodayPlan): List<TodayBlock> =
        TodayLayouts.visibleBlocks(layout, plan).filter { it.placement == Placement.Section }

    /**
     * Pending requests the viewer can answer: sent to them or to anyone, not by themselves, for a hand-off that has not
     * passed and whose occurrence is loaded. Sorted by when the hand-off is.
     */
    fun requests(
        me: String?,
        requests: List<HandoffRequest>,
        occurrences: List<EventOccurrence>,
        people: Map<String, FamilyPerson>,
        today: LocalDate,
        now: LocalTime,
    ): List<TodayRequest> {
        if (me == null) return emptyList()
        return requests
            .filter { it.status == RequestStatus.Pending && it.fromMemberId != me && (it.toMemberId == null || it.toMemberId == me) }
            .mapNotNull { r ->
                // A request names the series; a changed occurrence is an override that points back to it.
                val o = occurrences.firstOrNull { r.matches(it) } ?: return@mapNotNull null
                val pickUp = r.kind == HandoffKind.PickUp
                val date = if (pickUp && !o.allDay) o.end.date else o.start.date
                val time = if (pickUp) o.pickUpTime() else o.dropOffTime()
                val passed = date < today || (date == today && time != null && time < now)
                if (passed) return@mapNotNull null
                TodayRequest(
                    request = r,
                    from = people[r.fromMemberId],
                    children = o.attending.mapNotNull { people[it] }.filter { it.isChild },
                    date = date,
                    time = time,
                    occurrence = o,
                )
            }
            .sortedWith(compareBy({ it.date }, { it.time }))
    }

    /** A row of "Idag händer" is passed when its hand-off time or its event's end is behind [now]. Passed rows are dimmed. */
    fun isPassed(item: TimelineItem, date: LocalDate, now: LocalTime): Boolean = when (item) {
        is TimelineItem.HandoffItem -> item.time < now
        is TimelineItem.EventItem -> !item.occurrence.allDay && item.occurrence.end <= LocalDateTime(date, now)
    }

    /**
     * At most [max] rows of "Idag händer": everything still to come first in line, then as many of the latest passed rows
     * as there is room for, in time order. The rest is one tap away ("Visa hela dagen").
     */
    fun timelineWindow(items: List<TimelineItem>, date: LocalDate, now: LocalTime, max: Int = MAX_TIMELINE_ROWS): List<TimelineItem> {
        if (items.size <= max) return items
        val (passed, coming) = items.partition { isPassed(it, date, now) }
        val shown = (passed.takeLast((max - coming.size).coerceAtLeast(0)) + coming.take(max)).toSet()
        return items.filter { it in shown }
    }

    /** The list the shopping app opens on, the same choice as Handla: the chosen one, else the first grocery list. */
    fun activeList(lists: List<ShoppingList>, selected: String): ShoppingList? =
        lists.firstOrNull { it.id == selected } ?: lists.firstOrNull { it.kind == ListKind.Grocery } ?: lists.firstOrNull()

    /** What "Inköpslistan" needs from the active list: how much is left and who is shopping from it. */
    fun snapshot(
        lists: List<ShoppingList>,
        items: List<ShoppingItem>,
        presence: List<ShoppingPresence>,
        selected: String,
    ): ShoppingSnapshot? {
        val list = activeList(lists, selected) ?: return null
        return ShoppingSnapshot(list, items.count { it.listId == list.id && !it.checked }, presence.filter { it.listId == list.id })
    }

    /** "Veckohandling: 5 kvar" and who else is in the shop right now (presence expires by itself). */
    fun shopping(snapshot: ShoppingSnapshot?, people: Map<String, FamilyPerson>, me: String?, now: Instant): TodayShopping? {
        snapshot ?: return null
        return TodayShopping(
            listId = snapshot.list.id,
            listName = snapshot.list.name,
            remaining = snapshot.remaining,
            shoppers = snapshot.presence.filter { it.until > now && it.memberId != me }.mapNotNull { people[it.memberId] },
        )
    }
}

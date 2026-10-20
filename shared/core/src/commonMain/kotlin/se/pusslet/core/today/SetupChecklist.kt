package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.Family
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.weather.ClothingAdvisor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What "Kom igång" reads (UX plan 4.4). Everything comes from the family's own data, so no step is ever ticked by hand. */
data class SetupInput(
    val today: LocalDate,
    val now: Instant,
    /** The viewer's role: only parents set the family up. */
    val role: Role,
    /** The viewer's member id; the viewer is never asked to invite themselves. */
    val me: String?,
    val family: Family?,
    /** The example family ("Familjen Lind") is not a new family; it has "Börja på riktigt" instead (4.8). */
    val demo: Boolean = false,
    /** "Dölj" was tapped on this phone for this family. */
    val hidden: Boolean = false,
    val members: List<Member> = emptyList(),
    val children: List<Child> = emptyList(),
    /** All of the family's own events. */
    val events: List<Event> = emptyList(),
    /** Occurrences from today on (Idag's window), with the helpers' accepted answers applied. */
    val occurrences: List<EventOccurrence> = emptyList(),
    val mealWeeks: List<MealPlanWeek> = emptyList(),
    /** Anything on any shopping list. */
    val hasShoppingItems: Boolean = false,
    /** Invites need a backend; without one "Bjud in Johan" could never tick itself off, so it is not asked. */
    val canInvite: Boolean = false,
)

/** One step of "Kom igång". [key] is stable per step (and per child or member), for lists and tests. */
sealed interface SetupItem {
    val key: String
    val done: Boolean

    /** "Lägg till Ellas skola", or "Lägg till Simons förskola" for a child under six. */
    data class School(val child: Child, val preschool: Boolean, override val done: Boolean) : SetupItem {
        override val key: String get() = "school-${child.id}"
    }

    /**
     * "Vem lämnar och hämtar?": school and preschool days where nobody is set to drop off or pick up. [next] is the first
     * such day, the one a tap opens; null when every school day has its people.
     */
    data class Handoffs(val next: EventOccurrence?) : SetupItem {
        override val key: String get() = "handoffs"
        override val done: Boolean get() = next == null
    }

    /** "Bjud in Johan": an adult of the family who has no account yet. Done when they have joined. */
    data class Invite(val member: Member, override val done: Boolean) : SetupItem {
        override val key: String get() = "invite-${member.id}"
    }

    /** "Lägg till en aktivitet": the family's first own event that is not a school block. */
    data class Activity(override val done: Boolean) : SetupItem {
        override val key: String get() = "activity"
    }

    /** "Planera veckans middagar": a dinner planned in any week. */
    data class Dinners(override val done: Boolean) : SetupItem {
        override val key: String get() = "dinners"
    }

    /** "Lägg till en vara": something on a shopping list. */
    data class Shopping(override val done: Boolean) : SetupItem {
        override val key: String get() = "shopping"
    }
}

/** "Kom igång" as Idag shows it: "2 av 6 klart", the next steps and "Visa alla". */
data class SetupProgress(val items: List<SetupItem>) {
    val done: Int get() = items.count { it.done }
    val total: Int get() = items.size

    /** The card before "Visa alla": the next open steps, at most [SetupChecklist.MAX_VISIBLE]. */
    val next: List<SetupItem> get() = items.filterNot { it.done }.take(SetupChecklist.MAX_VISIBLE)

    /** "Visa alla" is offered when the card does not already show every step. */
    val hasMore: Boolean get() = total > next.size
}

/**
 * "Kom igång" on Idag (UX plan 4.4): a checklist for a new family that ticks itself off from the family's data. It sits
 * with "Att lösa" (what a new family needs to sort out), so it is not a block of "Anpassa Idag" (ADR-027): it goes away by
 * itself when everything is done, after "Dölj", or when the family is no longer new.
 */
object SetupChecklist {
    /** Rows on the card before "Visa alla". */
    const val MAX_VISIBLE = 3

    /** A family is new for its first four weeks; after that "Kom igång" no longer shows, done or not. */
    val NEW_FAMILY_FOR: Duration = 28.days

    /** Preschool starts at one; a child under six goes to "förskola". After 18 there is no school to add. */
    private const val PRESCHOOL_FROM = 1
    private const val SCHOOL_FROM = 6
    private const val SCHOOL_UNTIL = 18

    /** What Idag shows, or null: not a parent, the example family, hidden, not a new family, or nothing left to do. */
    fun forIdag(input: SetupInput): SetupProgress? {
        if (input.role != Role.Parent || input.demo || input.hidden || !isNew(input.family, input.now)) return null
        val items = items(input)
        if (items.isEmpty() || items.all { it.done }) return null
        return SetupProgress(items)
    }

    /** Every step that applies to this family, in the order they are suggested, each done or not. */
    fun items(input: SetupInput): List<SetupItem> = buildList {
        input.children.forEach { child ->
            val age = ClothingAdvisor.ageOn(child.birthDate, input.today)
            if (age == null || age in PRESCHOOL_FROM..SCHOOL_UNTIL) {
                add(SetupItem.School(child, preschool = age != null && age < SCHOOL_FROM, done = child.school != null))
            }
        }
        handoffs(input)?.let(::add)
        if (input.canInvite) {
            input.members
                .filter { it.id != input.me && it.role != Role.Child }
                .forEach { add(SetupItem.Invite(it, done = it.uid != null || it.joinedAt != null)) }
        }
        add(SetupItem.Activity(done = input.events.any { !TodayPlanner.isRoutineEvent(it) }))
        add(SetupItem.Dinners(done = input.mealWeeks.any { w -> w.entries.values.any { it.slot == MealSlot.Dinner } }))
        add(SetupItem.Shopping(done = input.hasShoppingItems))
    }

    /** True for the first [NEW_FAMILY_FOR] after the family was created; a family without a creation time is not new. */
    fun isNew(family: Family?, now: Instant): Boolean {
        val created = family?.createdAt ?: return false
        return now - created < NEW_FAMILY_FOR
    }

    /**
     * Only once the family has school or preschool days: a day nobody drops off or picks up from, or where a hand-off is
     * planned but has nobody, still needs its people. Subscribed calendars cannot get people, so they do not count.
     */
    private fun handoffs(input: SetupInput): SetupItem.Handoffs? {
        val childIds = input.children.mapTo(HashSet()) { it.id }
        val schoolDays = input.occurrences
            .filter { o ->
                o.source == EventSource.Family && TodayPlanner.isRoutine(o) && o.start.date >= input.today &&
                    o.event.participantIds.any { it in childIds }
            }
            .sortedBy { it.start }
        if (schoolDays.isEmpty()) return null
        return SetupItem.Handoffs(schoolDays.firstOrNull(::needsPeople))
    }

    private fun needsPeople(o: EventOccurrence): Boolean {
        val dropOff = o.event.dropOff
        val pickUp = o.event.pickUp
        if (dropOff == null && pickUp == null) return true
        return (dropOff != null && dropOff.personId == null) || (pickUp != null && pickUp.personId == null)
    }
}

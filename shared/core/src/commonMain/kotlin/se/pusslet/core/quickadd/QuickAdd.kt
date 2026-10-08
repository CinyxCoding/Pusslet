package se.pusslet.core.quickadd

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.units.Quantity

/** What the parser needs to know about "now" and the family. */
data class QuickAddContext(
    val today: LocalDate,
    val now: LocalTime,
    val people: List<FamilyPerson>,
    val currentUserId: String? = null,
)

/** Fields the preview highlights so the user can correct them with one tap. */
enum class UncertainField { Kind, Title, Date, Time, EndTime, Person, Recurrence }

data class ParsedItem(val name: String, val quantity: Quantity? = null)

sealed interface QuickAddAction {
    data class CreateEvent(
        val title: String,
        val date: LocalDate,
        val startTime: LocalTime? = null,
        val endTime: LocalTime? = null,
        /** Inclusive last day for multi-day all-day events. */
        val endDate: LocalDate? = null,
        val participantIds: List<String> = emptyList(),
        val recurrence: RRule? = null,
        val location: String = "",
        val tags: List<String> = emptyList(),
        val packingItems: List<String> = emptyList(),
    ) : QuickAddAction {
        val allDay: Boolean get() = startTime == null
    }

    data class CreateTask(
        val title: String,
        val assigneeId: String? = null,
        val due: LocalDate? = null,
        /** True when the task belongs to the event in the same input ("…, köp present"). */
        val linkedToEvent: Boolean = false,
        val relatedPersonIds: List<String> = emptyList(),
    ) : QuickAddAction

    data class AddShoppingItems(val items: List<ParsedItem>) : QuickAddAction

    data class PlanMeal(val date: LocalDate, val title: String, val slot: MealSlot = MealSlot.Dinner) : QuickAddAction

    data class AssignHandoff(
        val personId: String,
        val kind: HandoffKind,
        val date: LocalDate,
        /** Empty = every child that has a drop-off/pick-up that day. */
        val childIds: List<String> = emptyList(),
        val time: LocalTime? = null,
        val recurringDays: Set<DayOfWeek> = emptySet(),
    ) : QuickAddAction
}

data class QuickAddResult(
    val input: String,
    val actions: List<QuickAddAction>,
    val uncertain: Set<UncertainField> = emptySet(),
) {
    val isEmpty: Boolean get() = actions.isEmpty()

    /** 1.0 = fully understood. Each uncertain field lowers confidence. */
    val confidence: Double get() = if (actions.isEmpty()) 0.0 else (1.0 - 0.15 * uncertain.size).coerceAtLeast(0.1)

    val primary: QuickAddAction? get() = actions.firstOrNull()
}

package se.pusslet.core.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.units.QuantityParser

enum class ActionKind { Event, Task, Shopping, Meal, Handoff, Sick }

/** One-tap corrections in the quick-add preview. Pure, so the preview behaves predictably and is unit tested. */
object QuickAddEdits {
    fun kindOf(action: QuickAddAction): ActionKind = when (action) {
        is QuickAddAction.CreateEvent -> ActionKind.Event
        is QuickAddAction.CreateTask -> ActionKind.Task
        is QuickAddAction.AddShoppingItems -> ActionKind.Shopping
        is QuickAddAction.PlanMeal -> ActionKind.Meal
        is QuickAddAction.AssignHandoff -> ActionKind.Handoff
        is QuickAddAction.ReportSick -> ActionKind.Sick
    }

    fun titleOf(action: QuickAddAction): String = when (action) {
        is QuickAddAction.CreateEvent -> action.title
        is QuickAddAction.CreateTask -> action.title
        is QuickAddAction.AddShoppingItems -> action.items.joinToString(", ") { it.name }
        is QuickAddAction.PlanMeal -> action.title
        is QuickAddAction.AssignHandoff -> ""
        is QuickAddAction.ReportSick -> ""
    }

    fun dateOf(action: QuickAddAction): LocalDate? = when (action) {
        is QuickAddAction.CreateEvent -> action.date
        is QuickAddAction.CreateTask -> action.due
        is QuickAddAction.AddShoppingItems -> null
        is QuickAddAction.PlanMeal -> action.date
        is QuickAddAction.AssignHandoff -> action.date
        is QuickAddAction.ReportSick -> action.date
    }

    /** Converts the parser's guess to the kind the user picked, keeping title, date and people where it makes sense. */
    fun convert(action: QuickAddAction, to: ActionKind, today: LocalDate): QuickAddAction {
        if (kindOf(action) == to) return action
        val title = titleOf(action).ifBlank { "" }
        val date = dateOf(action) ?: today
        val people = when (action) {
            is QuickAddAction.CreateEvent -> action.participantIds
            is QuickAddAction.CreateTask -> listOfNotNull(action.assigneeId) + action.relatedPersonIds
            is QuickAddAction.AssignHandoff -> action.childIds
            is QuickAddAction.ReportSick -> action.childIds
            else -> emptyList()
        }
        return when (to) {
            ActionKind.Event -> QuickAddAction.CreateEvent(title = title, date = date, participantIds = people)
            ActionKind.Task -> QuickAddAction.CreateTask(title = title, due = dateOf(action), relatedPersonIds = people)
            ActionKind.Shopping -> QuickAddAction.AddShoppingItems(
                title.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
                    val q = QuantityParser.parsePrefix(part)
                    ParsedItem(q.rest.ifEmpty { part }, q.quantity)
                },
            )
            ActionKind.Meal -> QuickAddAction.PlanMeal(date = date, title = title)
            ActionKind.Handoff, ActionKind.Sick -> action
        }
    }

    fun withTitle(action: QuickAddAction, title: String): QuickAddAction = when (action) {
        is QuickAddAction.CreateEvent -> action.copy(title = title)
        is QuickAddAction.CreateTask -> action.copy(title = title)
        is QuickAddAction.PlanMeal -> action.copy(title = title)
        is QuickAddAction.AddShoppingItems -> convert(QuickAddAction.CreateTask(title), ActionKind.Shopping, LocalDate(2000, 1, 1))
        is QuickAddAction.AssignHandoff -> action
        is QuickAddAction.ReportSick -> action
    }

    fun withDate(action: QuickAddAction, date: LocalDate): QuickAddAction = when (action) {
        is QuickAddAction.CreateEvent -> action.copy(
            date = date,
            endDate = action.endDate?.let { end ->
                LocalDate.fromEpochDays(
                    date.toEpochDays() + (end.toEpochDays() - action.date.toEpochDays()),
                )
            },
        )
        is QuickAddAction.CreateTask -> action.copy(due = date)
        is QuickAddAction.PlanMeal -> action.copy(date = date)
        is QuickAddAction.AssignHandoff -> action.copy(date = date)
        is QuickAddAction.ReportSick -> action.copy(date = date)
        is QuickAddAction.AddShoppingItems -> action
    }

    /** Setting a start time keeps the event's length; clearing it makes the event all-day. */
    fun withStart(action: QuickAddAction.CreateEvent, start: LocalTime?): QuickAddAction.CreateEvent {
        if (start == null) return action.copy(startTime = null, endTime = null)
        val old = action.startTime
        val oldEnd = action.endTime
        val length = if (old != null && oldEnd != null) minutes(oldEnd) - minutes(old) else 60
        val endMin = (minutes(start) + length.coerceAtLeast(15)).coerceAtMost(23 * 60 + 59)
        return action.copy(startTime = start, endTime = LocalTime(endMin / 60, endMin % 60), endDate = null)
    }

    fun withEnd(action: QuickAddAction.CreateEvent, end: LocalTime): QuickAddAction.CreateEvent = action.copy(endTime = end)

    fun togglePerson(action: QuickAddAction, personId: String): QuickAddAction = when (action) {
        is QuickAddAction.CreateEvent -> action.copy(participantIds = toggle(action.participantIds, personId))
        is QuickAddAction.CreateTask -> action.copy(assigneeId = if (action.assigneeId == personId) null else personId)
        is QuickAddAction.AssignHandoff -> action.copy(childIds = toggle(action.childIds, personId))
        is QuickAddAction.ReportSick -> action.copy(childIds = toggle(action.childIds, personId))
        else -> action
    }

    private fun toggle(list: List<String>, id: String) = if (id in list) list - id else list + id

    private fun minutes(t: LocalTime) = t.hour * 60 + t.minute
}

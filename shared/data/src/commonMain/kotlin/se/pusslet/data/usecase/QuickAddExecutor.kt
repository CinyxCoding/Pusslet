package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.WeekdayNum
import se.pusslet.core.shopping.AisleClassifier
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** What was saved, for the confirmation message and "Ångra". */
data class QuickAddOutcome(
    /** Events created (undo deletes them). */
    val eventIds: List<String> = emptyList(),
    /** Existing events whose hand-off was changed (not undone). */
    val modifiedEventIds: List<String> = emptyList(),
    val taskIds: List<String> = emptyList(),
    val itemIds: List<String> = emptyList(),
    val meals: List<Pair<LocalDate, MealSlot>> = emptyList(),
    /** True when a hand-off had to be created because the day had none. */
    val createdHandoffEvent: Boolean = false,
)

/** Applies the parser's actions (possibly corrected by the user in the preview) to the repositories. */
class QuickAddExecutor(
    private val calendar: CalendarRepository,
    private val tasks: TaskRepository,
    private val shopping: ShoppingRepository,
    private val meals: MealRepository,
    private val recipes: RecipeRepository,
    private val family: FamilyRepository,
    private val handoffs: HandoffAssigner,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
) {
    suspend fun execute(result: QuickAddResult): QuickAddOutcome {
        var outcome = QuickAddOutcome()
        var lastEventId: String? = null
        val me = sessions.session.value.memberId
        for (action in result.actions) {
            when (action) {
                is QuickAddAction.CreateEvent -> {
                    val event = toEvent(action, me)
                    calendar.saveEvent(event)
                    lastEventId = event.id
                    outcome = outcome.copy(eventIds = outcome.eventIds + event.id)
                }
                is QuickAddAction.CreateTask -> {
                    val task = FamilyTask(
                        id = ids.newId(),
                        title = action.title,
                        assigneeId = action.assigneeId ?: if (action.linkedToEvent) me else null,
                        due = action.due,
                        linkedEventId = if (action.linkedToEvent) lastEventId else null,
                        category = if (action.linkedToEvent &&
                            action.title.contains("present", ignoreCase = true)
                        ) {
                            TaskCategory.Gift
                        } else {
                            TaskCategory.General
                        },
                        createdBy = me,
                        createdAt = clock.now(),
                    )
                    tasks.save(task)
                    task.linkedEventId?.let { eventId ->
                        calendar.event(eventId)?.let { e -> calendar.saveEvent(e.copy(linkedTaskIds = e.linkedTaskIds + task.id)) }
                    }
                    outcome = outcome.copy(taskIds = outcome.taskIds + task.id)
                }
                is QuickAddAction.AddShoppingItems -> {
                    val listId = targetListId()
                    val overrides = shopping.aisleOverrides.first()
                    val items = action.items.map { p ->
                        ShoppingItem(
                            id = ids.newId(),
                            listId = listId,
                            name = p.name.replaceFirstChar { it.uppercase() },
                            quantity = p.quantity?.value,
                            unit = p.quantity?.unit,
                            aisle = AisleClassifier.classify(p.name, overrides),
                            addedBy = me,
                            addedAt = clock.now(),
                        )
                    }
                    shopping.saveItems(items)
                    outcome = outcome.copy(itemIds = outcome.itemIds + items.map { it.id })
                }
                is QuickAddAction.PlanMeal -> {
                    val recipe = recipes.all().firstOrNull { r ->
                        r.title.equals(action.title, ignoreCase = true) || r.title.lowercase().startsWith(action.title.lowercase())
                    }
                    meals.setEntry(MealEntry(action.date, action.slot, recipe?.id, recipe?.title ?: action.title))
                    outcome = outcome.copy(meals = outcome.meals + (action.date to action.slot))
                }
                is QuickAddAction.AssignHandoff -> outcome = assignHandoff(action, outcome)
            }
        }
        if (result.input.isNotBlank()) {
            prefs.update(Prefs.RecentQuickAdds) { (listOf(result.input.trim()) + it).distinct().take(RECENT) }
        }
        return outcome
    }

    /** Removes what [execute] created. Meal entries are cleared; changed hand-offs stay. */
    suspend fun undo(outcome: QuickAddOutcome) {
        outcome.eventIds.forEach { calendar.deleteEvent(it) }
        outcome.taskIds.forEach { tasks.delete(it) }
        outcome.itemIds.forEach { shopping.deleteItem(it) }
        outcome.meals.forEach { (date, slot) -> meals.clearEntry(date, slot) }
    }

    private suspend fun assignHandoff(action: QuickAddAction.AssignHandoff, outcome: QuickAddOutcome): QuickAddOutcome {
        val children = action.childIds.ifEmpty { family.children.first().map { it.id } }
        val dayOccurrences = calendar.occurrences(action.date, action.date, zone).first().filter { o ->
            o.event.participantIds.any { it in children } &&
                (if (action.kind == HandoffKind.PickUp) o.event.pickUp != null else o.event.dropOff != null)
        }
        if (dayOccurrences.isNotEmpty() && action.recurringDays.isEmpty()) {
            dayOccurrences.forEach { handoffs.assign(it, action.kind, action.personId, action.time) }
            return outcome.copy(modifiedEventIds = outcome.modifiedEventIds + dayOccurrences.map { it.event.id })
        }
        val time = action.time ?: if (action.kind == HandoffKind.PickUp) LocalTime(16, 0) else LocalTime(8, 0)
        val rrule = action.recurringDays.takeIf { it.isNotEmpty() }?.let { days ->
            RRule(Frequency.WEEKLY, byDay = days.sortedBy { it.ordinal }.map { WeekdayNum(it) }).format()
        }
        val event = handoffs.createStandalone(
            id = ids.newId(),
            title = if (action.kind == HandoffKind.PickUp) "Hämtning" else "Lämning",
            date = action.date,
            time = time,
            kind = action.kind,
            personId = action.personId,
            childIds = children,
            rrule = rrule,
        )
        return outcome.copy(eventIds = outcome.eventIds + event.id, createdHandoffEvent = true)
    }

    private fun toEvent(a: QuickAddAction.CreateEvent, me: String?): Event {
        val startTime = a.startTime
        val endTime = a.endTime
        val start = LocalDateTime(a.date, startTime ?: LocalTime(0, 0))
        val end = when {
            startTime == null -> LocalDateTime((a.endDate ?: a.date).plusDays(1), LocalTime(0, 0))
            endTime != null && endTime > startTime -> LocalDateTime(a.date, endTime)
            endTime != null -> LocalDateTime(a.date.plusDays(1), endTime)
            else -> EventExpander.addWallClock(start, 1.hours)
        }
        return Event(
            id = ids.newId(),
            title = a.title,
            start = start,
            end = end,
            allDay = a.startTime == null,
            timeZoneId = zone.id,
            location = a.location,
            rrule = a.recurrence?.format(),
            participantIds = a.participantIds,
            tags = a.tags,
            packingItems = a.packingItems,
            createdBy = me,
        )
    }

    /** The active grocery list, or a new "Veckohandling" when the family has none yet. */
    private suspend fun targetListId(): String {
        val lists = shopping.lists.first()
        val active = prefs.get(Prefs.ActiveShoppingList)
        lists.firstOrNull { it.id == active }?.let { return it.id }
        lists.firstOrNull { it.kind == ListKind.Grocery }?.let { return it.id }
        val list = ShoppingList(ids.newId(), DEFAULT_LIST_NAME)
        shopping.saveList(list)
        return list.id
    }

    private companion object {
        const val RECENT = 8
        const val DEFAULT_LIST_NAME = "Veckohandling"
    }
}

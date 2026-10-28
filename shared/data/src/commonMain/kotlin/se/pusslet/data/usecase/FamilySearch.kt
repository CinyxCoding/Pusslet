package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.search.TextSearch
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import kotlin.time.Clock

data class EventHit(val occurrence: EventOccurrence, val score: Int)

data class RecipeHit(val recipeId: String, val title: String, val minutes: Int?, val score: Int)

data class TaskHit(val taskId: String, val title: String, val due: LocalDate?, val done: Boolean, val score: Int)

data class ItemHit(val itemId: String, val listId: String, val listName: String, val name: String, val checked: Boolean, val score: Int)

data class ContactHit(
    val childId: String,
    val childName: String,
    val name: String,
    val relation: String,
    val phone: String?,
    val score: Int,
)

data class SearchResults(
    val query: String = "",
    val events: List<EventHit> = emptyList(),
    val recipes: List<RecipeHit> = emptyList(),
    val tasks: List<TaskHit> = emptyList(),
    val items: List<ItemHit> = emptyList(),
    val contacts: List<ContactHit> = emptyList(),
) {
    val isEmpty: Boolean get() = events.isEmpty() && recipes.isEmpty() && tasks.isEmpty() && items.isEmpty() && contacts.isEmpty()
}

/**
 * "Sök" over everything on this phone: events (family, subscriptions and the phone's own calendars), recipes, to-dos,
 * shopping items and the children's contacts. Local and rule based (ADR-001, ADR-052); see [TextSearch] for the matching.
 * A recurring event is shown once, at its next time (or its latest, if it is over).
 */
class FamilySearch(
    private val calendar: CalendarRepository,
    private val recipes: RecipeRepository,
    private val tasks: TaskRepository,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    suspend fun search(query: String): SearchResults {
        val words = TextSearch.words(query)
        if (words.isEmpty()) return SearchResults(query)
        val today = clock.todayIn(zone)
        return SearchResults(
            query = query,
            events = events(words, today),
            recipes = recipes.all()
                .mapNotNull { r ->
                    val score = TextSearch.score(words, r.title, r.ingredients.map { it.name } + r.notes)
                    if (score > 0) RecipeHit(r.id, r.title, r.totalMinutes, score) else null
                }
                .sortedByDescending { it.score }
                .take(LIMIT),
            tasks = tasks.tasks.first()
                .mapNotNull { t ->
                    val score = TextSearch.score(words, t.title, listOf(t.note) + t.checklist.map { it.text })
                    if (score > 0) TaskHit(t.id, t.title, t.due, t.done, score) else null
                }
                .sortedWith(compareByDescending<TaskHit> { it.score }.thenBy { it.done })
                .take(LIMIT),
            items = items(words),
            contacts = family.children.first()
                .flatMap { child ->
                    child.contacts.mapNotNull { c ->
                        val score = TextSearch.score(words, c.name, listOf(c.relation, c.phone.orEmpty(), child.firstName))
                        if (score > 0) ContactHit(child.id, child.firstName, c.name, c.relation, c.phone, score) else null
                    }
                }
                .sortedByDescending { it.score }
                .take(LIMIT),
        )
    }

    private suspend fun events(words: List<String>, today: LocalDate): List<EventHit> {
        val occurrences = calendar.allOccurrences(today.plusDays(-PAST_DAYS), today.plusDays(FUTURE_DAYS), zone).first()
            .filter { it.source != EventSource.Holiday }
        return occurrences
            .groupBy { (it.event.seriesId ?: it.event.id) + "@" + it.source }
            .values
            .mapNotNull { series ->
                val shown = series.filter { it.end.date >= today }.minByOrNull { it.start } ?: series.maxBy { it.start }
                val e = shown.event
                val score = TextSearch.score(words, e.title, listOf(e.location, e.note) + e.tags + listOfNotNull(shown.sourceName))
                if (score > 0) EventHit(shown, score) else null
            }
            // Best match first; among equals, what comes soonest.
            .sortedWith(
                compareByDescending<EventHit> { it.score }.thenBy {
                    kotlin.math.abs(
                        it.occurrence.start.date.toEpochDays() - today.toEpochDays(),
                    )
                },
            )
            .take(LIMIT)
    }

    private suspend fun items(words: List<String>): List<ItemHit> {
        val lists = shopping.lists.first().associateBy { it.id }
        return shopping.items.first()
            .mapNotNull { item ->
                val list = lists[item.listId] ?: return@mapNotNull null
                val score = TextSearch.score(words, item.name, listOf(item.note))
                if (score > 0) ItemHit(item.id, list.id, list.name, item.name, item.checked, score) else null
            }
            .sortedWith(compareByDescending<ItemHit> { it.score }.thenBy { it.checked })
            .take(LIMIT)
    }

    private companion object {
        const val LIMIT = 20
        const val PAST_DAYS = 180
        const val FUTURE_DAYS = 365
    }
}

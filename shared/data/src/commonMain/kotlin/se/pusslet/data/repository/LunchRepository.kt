package se.pusslet.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.time.isoWeek
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore

/** School lunches per child: from a feed (skolmaten.se / RSS) or typed in manually (synced to the family). */
@OptIn(ExperimentalCoroutinesApi::class)
class LunchRepository(
    private val docs: DocumentStore,
    private val provider: LunchMenuProvider,
    private val family: FamilyRepository,
) {
    val manual: Flow<List<ManualLunchWeek>> = docs.observeAll(Collections.SchoolLunches, ManualLunchWeek.serializer())

    /** childId -> lunches (current week from feeds, all manual entries). */
    val lunches: Flow<Map<String, List<SchoolLunch>>> = family.children.flatMapLatest { children ->
        val feeds = children.filter { it.lunch?.provider == LunchProviderType.Skolmaten || it.lunch?.provider == LunchProviderType.Rss }
        val fetched: Flow<Map<String, List<SchoolLunch>>> = if (feeds.isEmpty()) {
            flowOf(emptyMap())
        } else {
            flow { emit(feeds.associate { c -> c.id to fetch(c) }) }
        }
        combine(manual, fetched) { weeks, fromFeeds ->
            children.associate { c ->
                val own = weeks.filter { it.childId == c.id }.flatMap { it.lunches(MANUAL_SOURCE) }
                c.id to (fromFeeds[c.id].orEmpty() + own).distinctBy { it.date }.sortedBy { it.date }
            }
        }
    }

    private suspend fun fetch(child: Child): List<SchoolLunch> {
        val source = child.lunch ?: return emptyList()
        return provider.lunches(source)?.value.orEmpty()
    }

    suspend fun setManual(childId: String, date: LocalDate, dishes: String) {
        val week = date.isoWeek
        val id = ManualLunchWeek.docId(childId, week)
        val current = docs.get(Collections.SchoolLunches, id, ManualLunchWeek.serializer()) ?: ManualLunchWeek(childId, week)
        val days = if (dishes.isBlank()) current.days - date.toString() else current.days + (date.toString() to dishes.trim())
        docs.put(Collections.SchoolLunches, id, current.copy(days = days), ManualLunchWeek.serializer())
    }

    private companion object {
        const val MANUAL_SOURCE = "manual"
    }
}

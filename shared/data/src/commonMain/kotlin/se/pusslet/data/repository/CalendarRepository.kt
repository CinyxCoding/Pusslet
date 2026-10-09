package se.pusslet.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.data.db.CacheDao
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.DomainJson
import kotlin.time.Instant

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val accountName: String,
    val color: Int? = null,
    val writable: Boolean = false,
    /** The calendar this app owns for exporting family events. */
    val ownedByApp: Boolean = false,
)

/**
 * The phone's own calendars (Google, Exchange, Samsung …) via CalendarContract on Android.
 * Read events are attributed to the current member so conflicts can be detected.
 */
interface DeviceCalendarSource {
    fun hasPermission(): Boolean

    suspend fun calendars(): List<DeviceCalendar>

    suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate, ownerMemberId: String?): List<Event>

    /**
     * Writes/updates family events into [calendarId], identified by a stable UID. Changed single occurrences are written
     * as their own events and excluded from their series. Returns number of rows touched.
     */
    suspend fun exportEvents(calendarId: Long, events: List<Event>): Int

    /** Deletes events this app exported to [calendarId] that are no longer in [keepEventIds] (deleted in the family). */
    suspend fun removeExported(calendarId: Long, keepEventIds: Set<String>): Int

    /** Creates (or returns) a local calendar owned by the app. */
    suspend fun ensureAppCalendar(name: String): Long?

    /** Emits when the device calendars change. */
    fun changes(): Flow<Unit>
}

object NoDeviceCalendars : DeviceCalendarSource {
    override fun hasPermission() = false

    override suspend fun calendars(): List<DeviceCalendar> = emptyList()

    override suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate, ownerMemberId: String?): List<Event> = emptyList()

    override suspend fun exportEvents(calendarId: Long, events: List<Event>) = 0

    override suspend fun removeExported(calendarId: Long, keepEventIds: Set<String>) = 0

    override suspend fun ensureAppCalendar(name: String): Long? = null

    override fun changes(): Flow<Unit> = flowOf()
}

/** Status of one subscribed feed for the settings list. */
data class FeedStatus(val eventCount: Int, val fetchedAt: Instant?, val lastError: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRepository(
    private val docs: DocumentStore,
    private val cache: CacheDao,
    private val prefs: PreferencesRepository,
    private val deviceCalendars: DeviceCalendarSource,
    private val sessions: se.pusslet.data.session.SessionProvider,
    private val json: Json = DomainJson,
) {
    val events: Flow<List<Event>> = docs.observeAll(Collections.Events, Event.serializer())

    val subscriptions: Flow<List<CalendarSubscription>> = docs.observeAll(Collections.Subscriptions, CalendarSubscription.serializer())

    val handoffRequests: Flow<List<HandoffRequest>> = docs.observeAll(Collections.HandoffRequests, HandoffRequest.serializer())

    suspend fun saveEvent(event: Event) = docs.put(Collections.Events, event.id, event, Event.serializer())

    suspend fun event(id: String): Event? = docs.get(Collections.Events, id, Event.serializer())

    /** The space shared with the other home that the event lives in, or null when it is the family's own (ADR-030). */
    suspend fun sharedSpaceOf(id: String): String? = docs.rootOf(Collections.Events, id)?.takeIf { it != sessions.session.value.familyId }

    /** Moves an event and its changed occurrences into a shared space, or back to the family ([spaceId] = null). */
    suspend fun moveEvent(id: String, spaceId: String?) {
        val target = spaceId ?: sessions.session.value.familyId ?: return
        docs.getAll(Collections.Events, Event.serializer()).filter {
            it.seriesId == id
        }.forEach { docs.move(Collections.Events, it.id, target) }
        docs.move(Collections.Events, id, target)
    }

    suspend fun deleteEvent(id: String) {
        // Deleting a series also removes its per-occurrence overrides.
        docs.getAll(Collections.Events, Event.serializer()).filter { it.seriesId == id }.forEach { docs.delete(Collections.Events, it.id) }
        docs.delete(Collections.Events, id)
    }

    /** Removes a single occurrence of a recurring event. */
    suspend fun skipOccurrence(seriesId: String, date: LocalDate) {
        docs.update(Collections.Events, seriesId, Event.serializer()) { it.copy(exDates = (it.exDates + date).distinct()) }
    }

    suspend fun saveSubscription(sub: CalendarSubscription) = docs.put(
        Collections.Subscriptions,
        sub.id,
        sub,
        CalendarSubscription.serializer(),
    )

    suspend fun deleteSubscription(id: String) {
        docs.delete(Collections.Subscriptions, id)
        cache.deletePrefix(subscriptionCacheKey(id))
        cache.deletePrefix(subscriptionRawKey(id))
    }

    /** How many events a subscription has, when it was last fetched and whether the last attempt failed. */
    fun subscriptionStatus(
        id: String,
    ): Flow<FeedStatus> = combine(subscriptionEvents(id), cache.observe(subscriptionRawKey(id))) { evs, raw ->
        FeedStatus(evs.size, raw?.fetchedAt?.let { Instant.fromEpochMilliseconds(it) }, raw?.lastError)
    }

    suspend fun saveHandoffRequest(request: HandoffRequest) = docs.put(
        Collections.HandoffRequests,
        request.id,
        request,
        HandoffRequest.serializer(),
    )

    /** Cached, parsed events of one subscription (written by the ICS sync worker). */
    fun subscriptionEvents(id: String): Flow<List<Event>> = cache.observe(subscriptionCacheKey(id)).map { entry ->
        entry?.let { runCatching { json.decodeFromString(ListSerializer(Event.serializer()), it.body) }.getOrNull() }.orEmpty()
    }

    /** Every occurrence in [from]..[to] from the enabled layers, in [zone]. */
    fun occurrences(from: LocalDate, to: LocalDate, zone: TimeZone): Flow<List<EventOccurrence>> {
        val layers = prefs.observe(Prefs.CalendarLayers)
        val family = events.map { EventExpander.expand(it, from, to, zone, EventSource.Family) }
        val subs = subscriptions.flatMapLatest { list ->
            val enabled = list.filter { it.enabled }
            if (enabled.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(enabled.map { sub -> subscriptionEvents(sub.id).map { sub to it } }) { pairs ->
                    pairs.flatMap { (sub, evs) ->
                        EventExpander.expand(
                            evs.map { e ->
                                e.copy(participantIds = (e.participantIds + sub.personIds).distinct(), sharedWithCaregivers = true)
                            },
                            from,
                            to,
                            zone,
                            EventSource.Subscription,
                            sourceName = sub.name,
                            sourceColor = sub.color,
                        )
                    }
                }
            }
        }
        val device = combine(prefs.observe(Prefs.DeviceCalendarsRead), sessions.session) { ids, s -> ids to s.memberId }
            .flatMapLatest { (ids, me) ->
                if (ids.isEmpty() || !deviceCalendars.hasPermission()) {
                    flowOf(emptyList())
                } else {
                    deviceCalendars.changes().onStart { emit(Unit) }.flatMapLatest {
                        flow { emit(EventExpander.expand(deviceCalendars.events(ids, from, to, me), from, to, zone, EventSource.Device)) }
                    }
                }
            }
        return combine(layers, family, subs, device) { l, f, s, d ->
            buildList {
                if (CalendarLayer.Family in l) addAll(f)
                if (CalendarLayer.Subscriptions in l) addAll(s)
                if (CalendarLayer.Device in l) addAll(d)
            }.sortedWith(compareBy({ it.start }, { !it.allDay }))
        }
    }

    companion object {
        fun subscriptionCacheKey(id: String) = "ics:$id"

        /** The downloaded feed itself (CachedFetcher keeps fetchedAt and the last error here). */
        fun subscriptionRawKey(id: String) = "icsraw:$id"
    }
}

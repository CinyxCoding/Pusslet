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
import se.pusslet.core.model.BreakPlan
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.HandoffReceipt
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.SickDay
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheKeyDao
import se.pusslet.data.sources.SubscriptionStatus
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.DomainJson

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

    /** Writes/updates family events into [calendarId] (stable ids in SYNC_DATA). Returns number of rows touched. */
    suspend fun exportEvents(calendarId: Long, events: List<Event>): Int

    /** Deletes the app's exported copies in [calendarId] whose family event is not in [keep] (all of them for an empty set). */
    suspend fun removeExported(calendarId: Long, keep: Set<String>): Int

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

    override suspend fun removeExported(calendarId: Long, keep: Set<String>) = 0

    override suspend fun ensureAppCalendar(name: String): Long? = null

    override fun changes(): Flow<Unit> = flowOf()
}

/** What [CalendarRepository.deleteSubscription] removed, so "Ångra" can put it back as it was, events included. */
data class RemovedSubscription(val subscription: CalendarSubscription, val cached: List<CacheEntryEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRepository(
    private val docs: DocumentStore,
    private val cache: CacheDao,
    private val prefs: PreferencesRepository,
    private val deviceCalendars: DeviceCalendarSource,
    private val sessions: se.pusslet.data.session.SessionProvider,
    private val json: Json = DomainJson,
    /** Exact deletes of cache keys. The app always passes it (DataModule); without it a prefix delete is used. */
    private val cacheKeys: CacheKeyDao? = null,
) {
    val events: Flow<List<Event>> = docs.observeAll(Collections.Events, Event.serializer())

    val subscriptions: Flow<List<CalendarSubscription>> = docs.observeAll(Collections.Subscriptions, CalendarSubscription.serializer())

    val handoffRequests: Flow<List<HandoffRequest>> = docs.observeAll(Collections.HandoffRequests, HandoffRequest.serializer())

    /** Children home sick (ADR-047); [se.pusslet.core.sick.SickDays] applies them to the day's occurrences. */
    val sickDays: Flow<List<SickDay>> = docs.observeAll(Collections.SickDays, SickDay.serializer())

    /** The medicine log and courses (ADR-050), for Idag and the reminders; [MedicineRepository] writes them. */
    val medicineLog: Flow<List<MedicineEntry>> = docs.observeAll(Collections.MedicineLog, MedicineEntry.serializer())
    val medicineCourses: Flow<List<MedicineCourse>> = docs.observeAll(Collections.MedicineCourses, MedicineCourse.serializer())

    /** The plans for school breaks (ADR-051), for Idag and the reminders; [BreakRepository] writes them. */
    val breakPlans: Flow<List<BreakPlan>> = docs.observeAll(Collections.BreakPlans, BreakPlan.serializer())

    /** "Hämtad ✓" (ADR-048). */
    val handoffReceipts: Flow<List<HandoffReceipt>> = docs.observeAll(Collections.HandoffReceipts, HandoffReceipt.serializer())

    suspend fun saveHandoffReceipt(receipt: HandoffReceipt) =
        docs.put(Collections.HandoffReceipts, receipt.id, receipt, HandoffReceipt.serializer())

    suspend fun deleteHandoffReceipt(id: String) = docs.delete(Collections.HandoffReceipts, id)

    suspend fun saveEvent(event: Event) = docs.put(Collections.Events, event.id, event, Event.serializer())

    suspend fun event(id: String): Event? = docs.get(Collections.Events, id, Event.serializer())

    /** The space shared with the other home that the event lives in, or null when it is the family's own (ADR-056). */
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

    suspend fun subscription(id: String): CalendarSubscription? = docs.get(Collections.Subscriptions, id, CalendarSubscription.serializer())

    /**
     * Removes the subscription and what this phone cached for it: the download, the parsed events and the status, each by
     * its exact key (never another subscription whose id starts the same). Returns what was removed, for "Ångra".
     */
    suspend fun deleteSubscription(id: String): RemovedSubscription? {
        val sub = subscription(id)
        val keys = subscriptionKeys(id)
        val cached = keys.mapNotNull { cache.get(it) }
        docs.delete(Collections.Subscriptions, id)
        deleteCacheKeys(keys)
        return sub?.let { RemovedSubscription(it, cached) }
    }

    /** "Ångra" after [deleteSubscription]: the cache first, so the events are back at once, without a download. */
    suspend fun restoreSubscription(removed: RemovedSubscription) {
        removed.cached.forEach { cache.put(it) }
        saveSubscription(removed.subscription)
    }

    /** Forgets the download and the parsed events of [id] (the status stays), e.g. because its link changed. */
    suspend fun dropSubscriptionData(id: String) = deleteCacheKeys(listOf(subscriptionRawKey(id), subscriptionCacheKey(id)))

    /** The last refresh of [id] on this phone, or null when it was never refreshed here. */
    suspend fun subscriptionStatus(id: String): SubscriptionStatus? = cache.get(subscriptionStatusKey(id))?.let { decodeStatus(it) }

    suspend fun saveSubscriptionStatus(status: SubscriptionStatus) = cache.put(
        CacheEntryEntity(
            key = subscriptionStatusKey(status.subscriptionId),
            body = json.encodeToString(SubscriptionStatus.serializer(), status),
            fetchedAt = (status.fetchedAt ?: status.checkedAt)?.toEpochMilliseconds() ?: 0L,
        ),
    )

    /** The last refresh of every subscription, by id (missing: never refreshed on this phone). */
    fun subscriptionStatuses(): Flow<Map<String, SubscriptionStatus>> = subscriptions.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(list.map { sub -> cache.observe(subscriptionStatusKey(sub.id)).map { entry -> entry?.let { decodeStatus(it) } } }) {
                it.filterNotNull().associateBy { s -> s.subscriptionId }
            }
        }
    }

    // A status that does not decode (written by another app version) counts as never refreshed; the next refresh rewrites it.
    private fun decodeStatus(entry: CacheEntryEntity): SubscriptionStatus? =
        runCatching { json.decodeFromString(SubscriptionStatus.serializer(), entry.body) }.getOrNull()

    private suspend fun deleteCacheKeys(keys: List<String>) {
        val exact = cacheKeys
        if (exact != null) exact.delete(keys) else keys.forEach { cache.deletePrefix(it) }
    }

    suspend fun saveHandoffRequest(request: HandoffRequest) = docs.put(
        Collections.HandoffRequests,
        request.id,
        request,
        HandoffRequest.serializer(),
    )

    /** Cached, parsed events of one subscription (written by [se.pusslet.data.sources.SubscriptionSync]). */
    fun subscriptionEvents(id: String): Flow<List<Event>> = cache.observe(subscriptionCacheKey(id)).map { entry ->
        entry?.let { runCatching { json.decodeFromString(ListSerializer(Event.serializer()), it.body) }.getOrNull() }.orEmpty()
    }

    /** Every occurrence in [from]..[to] from the layers Kalender's filter shows (a view choice on this phone), in [zone]. */
    fun occurrences(from: LocalDate, to: LocalDate, zone: TimeZone): Flow<List<EventOccurrence>> =
        occurrencesIn(from, to, zone, prefs.observe(Prefs.CalendarLayers))

    /**
     * Every occurrence in [from]..[to], whatever Kalender's filter shows. For what must not depend on a view choice: the
     * helper view and its preview, writing in an accepted answer, a quick-add hand-off, or finding the occurrence a screen
     * was opened for.
     */
    fun allOccurrences(from: LocalDate, to: LocalDate, zone: TimeZone): Flow<List<EventOccurrence>> =
        occurrencesIn(from, to, zone, flowOf(CalendarLayer.entries.toSet()))

    private fun occurrencesIn(
        from: LocalDate,
        to: LocalDate,
        zone: TimeZone,
        layers: Flow<Set<CalendarLayer>>,
    ): Flow<List<EventOccurrence>> {
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
        /** The parsed events of a subscription, as a JSON list of [Event]. */
        fun subscriptionCacheKey(id: String) = "ics:$id"

        /** The downloaded feed, with its ETag and Last-Modified ([se.pusslet.data.sources.CachedFetcher]). */
        fun subscriptionRawKey(id: String) = "icsraw:$id"

        /** The last refresh ([SubscriptionStatus]) as JSON. */
        fun subscriptionStatusKey(id: String) = "icsstatus:$id"

        fun subscriptionKeys(id: String) = listOf(subscriptionRawKey(id), subscriptionCacheKey(id), subscriptionStatusKey(id))
    }
}

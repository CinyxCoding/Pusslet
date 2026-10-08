package se.pusslet.data.sources

import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.pusslet.core.ics.CalendarFeedParser
import se.pusslet.core.ics.FeedWindow
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Event
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.sync.DomainJson
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class SubscriptionStatus(val subscriptionId: String, val eventCount: Int, val fetchedAt: Instant?, val error: String?)

/**
 * Fetches ICS feeds (school platforms, sports clubs, TimeEdit …), parses them and caches the events for the calendar.
 * Runs every 6 hours from a background worker and on demand ("Uppdatera nu"). Read-only.
 */
class SubscriptionSync(
    private val fetcher: CachedFetcher,
    private val parser: CalendarFeedParser,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
    private val json: Json = DomainJson,
) {
    suspend fun refresh(sub: CalendarSubscription, force: Boolean = false): SubscriptionStatus {
        val url = normalizeUrl(sub.url) ?: return SubscriptionStatus(sub.id, 0, null, "invalid-url")
        val raw =
            fetcher.get(CalendarRepository.subscriptionRawKey(sub.id), url, maxAge = 6.hours, force = force) {
                it.contains("BEGIN:VCALENDAR")
            }
                ?: return SubscriptionStatus(sub.id, 0, null, "unreachable")
        val calendar = parser.parse(raw.value, "ics:${sub.id}:", zone)
        // Years of history are dropped: the calendar decodes this list every time it is shown.
        val events = FeedWindow.relevant(calendar.events, clock.todayIn(zone))
        fetcher.store(
            CalendarRepository.subscriptionCacheKey(sub.id),
            json.encodeToString(ListSerializer(Event.serializer()), events),
        )
        return SubscriptionStatus(sub.id, events.size, raw.fetchedAt, raw.error)
    }

    suspend fun refreshAll(subs: List<CalendarSubscription>, force: Boolean = false): List<SubscriptionStatus> =
        subs.filter { it.enabled }.map { refresh(it, force) }

    companion object {
        /** webcal:// links are plain HTTPS feeds. Cleartext http is upgraded (Android blocks cleartext). */
        fun normalizeUrl(input: String): String? {
            val t = input.trim()
            val url = when {
                t.startsWith("webcal://", ignoreCase = true) -> "https://" + t.substring(9)
                t.startsWith("webcals://", ignoreCase = true) -> "https://" + t.substring(10)
                t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
                t.startsWith("https://", ignoreCase = true) -> t
                else -> return null
            }
            return url.takeIf { it.length > 10 && ' ' !in it }
        }
    }
}

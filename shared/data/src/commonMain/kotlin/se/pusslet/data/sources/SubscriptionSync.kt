package se.pusslet.data.sources

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.pusslet.core.ics.CalendarFeedParser
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import se.pusslet.data.net.bodyAsTextAtMost
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.sync.DomainJson
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Why fetching a subscription failed. Never a link, host or name, so it is safe to store and log. */
@Serializable
enum class SubscriptionError {
    /** Not a webcal://, http:// or https:// link. */
    InvalidUrl,

    /** No answer: offline, unknown host, timeout. */
    Unreachable,

    /** The server answered with an error: not found, no access, server trouble. */
    Http,

    /** The answer was not a calendar, e.g. a login page. */
    NotCalendar,
}

/**
 * The last refresh of one subscription on this phone, kept in the cache table under
 * [CalendarRepository.subscriptionStatusKey]. [fetchedAt] is when the events shown were downloaded (null: never),
 * [checkedAt] the last attempt and [error] why that attempt failed (the older events stay).
 */
@Serializable
data class SubscriptionStatus(
    val subscriptionId: String,
    val eventCount: Int = 0,
    val fetchedAt: Instant? = null,
    val checkedAt: Instant? = null,
    val error: SubscriptionError? = null,
    /** Fingerprint of the link the events came from, to notice a changed link. Not the link itself. */
    val urlKey: String? = null,
)

/** What "Hämta och visa" found: the feed's own name (X-WR-CALNAME), how many events it has and the next few. */
data class SubscriptionPreview(val name: String?, val eventCount: Int, val upcoming: List<EventOccurrence>)

sealed interface PreviewResult {
    data class Found(val preview: SubscriptionPreview) : PreviewResult

    data class Failed(val error: SubscriptionError) : PreviewResult
}

/**
 * Fetches ICS feeds (school platforms, sports clubs, TimeEdit …), parses them and caches the events for the calendar,
 * with a status per subscription. Read-only. [SubscriptionRefresher] decides when; this class never throws for a bad
 * feed or a lost network (only for cancellation).
 */
class SubscriptionSync(
    private val fetcher: CachedFetcher,
    private val parser: CalendarFeedParser,
    private val calendar: CalendarRepository,
    private val http: HttpClient,
    private val zone: TimeZone,
    private val clock: Clock,
    private val json: Json = DomainJson,
) {
    private val log = Logger.withTag("Subscriptions")

    /**
     * Downloads [sub] unless the copy is younger than [MAX_AGE] (or [force]), parses it and stores the events and the status.
     * After a failure the last good events stay and the status says why. A changed link drops the old events first.
     */
    suspend fun refresh(sub: CalendarSubscription, force: Boolean = false): SubscriptionStatus {
        val now = clock.now()
        val previous = calendar.subscriptionStatus(sub.id)
        val url = normalizeUrl(sub.url) ?: return save(failed(sub.id, previous, now, SubscriptionError.InvalidUrl, previous?.urlKey))
        val urlKey = urlKey(url)
        // Another link: the cached events belong to another calendar, and its ETag must not be sent to the new address.
        val linkChanged = previous?.urlKey != null && previous.urlKey != urlKey
        if (linkChanged) calendar.dropSubscriptionData(sub.id)
        val kept = previous.takeUnless { linkChanged }
        val raw = fetcher.get(
            CalendarRepository.subscriptionRawKey(sub.id),
            url,
            maxAge = MAX_AGE,
            force = force || linkChanged,
            maxBytes = MAX_FEED_BYTES,
            validate = ::looksLikeCalendar,
        ) ?: return save(failed(sub.id, kept, now, SubscriptionError.Unreachable, urlKey))
        val parsed = parser.parse(raw.value, "ics:${sub.id}:", zone)
        // Stamped with the download's time: events parsed again from an old copy must not look fresh.
        fetcher.store(
            CalendarRepository.subscriptionCacheKey(sub.id),
            json.encodeToString(ListSerializer(Event.serializer()), parsed.events),
            fetchedAt = raw.fetchedAt,
        )
        val error = if (raw.stale) classify(raw.error) else null
        return save(SubscriptionStatus(sub.id, parsed.events.size, raw.fetchedAt, now, error, urlKey))
    }

    /** Refreshes every enabled subscription in [subs], one after the other; one broken feed never stops the rest. */
    suspend fun refreshAll(subs: List<CalendarSubscription>, force: Boolean = false): List<SubscriptionStatus> =
        subs.filter { it.enabled }.mapNotNull { sub ->
            try {
                refresh(sub, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No id, link or name: the class of the failure is enough to find the bug.
                log.w { "Subscription refresh failed (${e::class.simpleName})" }
                null
            }
        }

    /**
     * "Hämta och visa" before saving: one download of [url], nothing cached. Returns the feed's name, its number of events
     * and the next [count] occurrences from now, or why it did not work.
     */
    suspend fun preview(url: String, count: Int = PREVIEW_EVENTS): PreviewResult {
        val target = normalizeUrl(url) ?: return PreviewResult.Failed(SubscriptionError.InvalidUrl)
        val body = try {
            http.prepareGet(target).execute { response ->
                if (response.status.isSuccess()) response.bodyAsTextAtMost(MAX_FEED_BYTES) else null
            } ?: return PreviewResult.Failed(SubscriptionError.Http)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.i { "Subscription preview failed (${e::class.simpleName})" }
            return PreviewResult.Failed(SubscriptionError.Unreachable)
        }
        if (!looksLikeCalendar(body)) return PreviewResult.Failed(SubscriptionError.NotCalendar)
        val calendarData = parser.parse(body, "preview:", zone)
        val now = clock.now().toLocalDateTime(zone)
        val upcoming = EventExpander.expand(calendarData.events, now.date, now.date.plusDays(PREVIEW_DAYS), zone, EventSource.Subscription)
            .filter { it.end > now }
            .take(count)
        val name = calendarData.name?.trim()?.take(MAX_NAME_LENGTH)?.ifEmpty { null }
        return PreviewResult.Found(SubscriptionPreview(name, calendarData.events.size, upcoming))
    }

    private suspend fun save(status: SubscriptionStatus): SubscriptionStatus {
        calendar.saveSubscriptionStatus(status)
        return status
    }

    private fun failed(id: String, previous: SubscriptionStatus?, now: Instant, error: SubscriptionError, urlKey: String?) =
        (previous ?: SubscriptionStatus(id)).copy(checkedAt = now, error = error, urlKey = urlKey)

    companion object {
        /** A copy younger than this is used without asking the server. */
        val MAX_AGE = 6.hours

        /** The limits firestore.rules sets on icsSubscriptions. */
        const val MAX_URL_LENGTH = 2000
        const val MAX_NAME_LENGTH = 200

        /** Years of a busy school calendar fit many times over; a larger answer is not read (the link is the family's). */
        const val MAX_FEED_BYTES = 10L * 1024 * 1024

        private const val PREVIEW_EVENTS = 3
        private const val PREVIEW_DAYS = 366
        private const val HEX = 16

        /**
         * webcal:// links are plain HTTPS feeds. Cleartext http is upgraded (Android blocks cleartext). Null for anything that
         * is not a web link with a host, or that is longer than [MAX_URL_LENGTH].
         */
        fun normalizeUrl(input: String): String? {
            val t = input.trim()
            val url = when {
                t.startsWith("webcal://", ignoreCase = true) -> "https://" + t.substring(9)
                t.startsWith("webcals://", ignoreCase = true) -> "https://" + t.substring(10)
                t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
                t.startsWith("https://", ignoreCase = true) -> "https://" + t.substring(8)
                else -> return null
            }
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':')
            val validHost = host.length >= 3 && ('.' in host || host.equals("localhost", ignoreCase = true)) && !host.startsWith('.')
            return url.takeIf { validHost && t.length <= MAX_URL_LENGTH && it.none { c -> c.isWhitespace() } }
        }

        /** The feed must at least open a VCALENDAR; HTML login pages and JSON errors are not cached. */
        fun looksLikeCalendar(body: String): Boolean = body.contains("BEGIN:VCALENDAR", ignoreCase = true)

        /** A fingerprint to notice a new link; the link itself is never stored outside the subscription. */
        internal fun urlKey(url: String): String = url.hashCode().toUInt().toString(HEX) + ":" + url.length

        /** [CachedFetcher]'s failure messages ("Unexpected content from …", "HTTP 404 from …"); anything else is the network. */
        internal fun classify(reason: String?): SubscriptionError = when {
            reason == null -> SubscriptionError.Unreachable
            reason.startsWith("Unexpected content") -> SubscriptionError.NotCalendar
            reason.startsWith("HTTP ") -> SubscriptionError.Http
            else -> SubscriptionError.Unreachable
        }
    }
}

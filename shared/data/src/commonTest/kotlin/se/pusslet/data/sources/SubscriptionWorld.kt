package se.pusslet.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.TimeZone
import se.pusslet.core.ics.IcsParser
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakeCacheKeyDao
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Tuesday 20 October 2026, 08:00 in Stockholm: summer time ends on Sunday 25 October. */
val SUBSCRIPTION_START: Instant = Instant.parse("2026-10-20T06:00:00Z")

class MutableClock(var now: Instant = SUBSCRIPTION_START) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** Calendar servers by URL; everything else is 404. Records each request (URL and headers). */
class FeedServer {
    private val routes = HashMap<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val requests = ArrayList<HttpRequestData>()
    val urls: List<String> get() = requests.map { it.url.toString() }

    fun on(url: String, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        routes[url] = handler
    }

    fun calendar(url: String, body: String, etag: String? = null) = on(url) {
        val headers = listOfNotNull(HttpHeaders.ContentType to listOf("text/calendar"), etag?.let { HttpHeaders.ETag to listOf(it) })
        respond(body, HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
    }

    fun status(url: String, status: HttpStatusCode, body: String = "") = on(url) { respond(body, status) }

    /** The network is gone: the request throws, like a lost connection or an unknown host. */
    fun offline(url: String) = on(url) { throw OfflineException() }

    fun engine(dispatcher: CoroutineDispatcher? = null): MockEngine = MockEngine(
        MockEngineConfig().apply {
            if (dispatcher != null) this.dispatcher = dispatcher
            addHandler { request ->
                requests += request
                val route = routes[request.url.toString()]
                if (route != null) route(request) else respond("", HttpStatusCode.NotFound)
            }
        },
    )
}

class OfflineException : Exception("offline")

/**
 * Sara's phone with subscriptions: the real [CalendarRepository] and [SubscriptionSync] over the in-memory document store,
 * a fake cache table and [server]. [engineDispatcher] runs the HTTP engine on a test dispatcher for virtual-time tests.
 */
class SubscriptionWorld(
    val clock: Clock = MutableClock(),
    role: Role = Role.Parent,
    engineDispatcher: CoroutineDispatcher? = null,
) {
    val zone = TimeZone.of("Europe/Stockholm")
    val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = role))
    val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    val cache = FakeCacheDao()
    val keys = FakeCacheKeyDao(cache)
    val prefs = PreferencesRepository(FakePreferenceDao())
    val calendar = CalendarRepository(docs, cache, prefs, NoDeviceCalendars, sessions, cacheKeys = keys)
    val server = FeedServer()
    val http = HttpClient(server.engine(engineDispatcher))
    val sync = SubscriptionSync(CachedFetcher(http, cache, clock), IcsParser(), calendar, http, zone, clock)

    suspend fun subscribe(
        id: String,
        url: String,
        name: String = "Skolan",
        personIds: List<String> = listOf("c-ella"),
        enabled: Boolean = true,
    ): CalendarSubscription {
        val sub = CalendarSubscription(id, name, url, personIds, MemberColor.Ocean, enabled)
        calendar.saveSubscription(sub)
        return sub
    }

    fun entry(key: String) = cache.entries.value[key]
}

/** Two events of a school calendar: a weekly lesson across the end of summer time and a single parents' evening. */
fun schoolFeed(name: String = "Björkängsskolan 3B", lesson: String = "Idrott") = """
    BEGIN:VCALENDAR
    VERSION:2.0
    PRODID:-//Test//Skolplattform//SV
    X-WR-CALNAME:$name
    X-WR-TIMEZONE:Europe/Stockholm
    BEGIN:VEVENT
    UID:pe@skolan
    DTSTART;TZID=Europe/Stockholm:20261021T130000
    DTEND;TZID=Europe/Stockholm:20261021T140000
    RRULE:FREQ=WEEKLY;COUNT=6
    SUMMARY:$lesson
    END:VEVENT
    BEGIN:VEVENT
    UID:evening@skolan
    DTSTART:20261028T170000Z
    DTEND:20261028T183000Z
    SUMMARY:Föräldramöte
    END:VEVENT
    END:VCALENDAR
    """.trimIndent()

package se.pusslet.data.sources

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import se.pusslet.core.model.EventSource
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.repository.CalendarRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SubscriptionSyncTest {
    private val link = "https://skolan.example.se/kalender/3b.ics"

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun fetchesParsesAndCachesWithAStatus() = runTest {
        val world = SubscriptionWorld()
        world.server.calendar(link, schoolFeed(), etag = "\"v1\"")
        val sub = world.subscribe("s1", "webcal://skolan.example.se/kalender/3b.ics")

        val status = world.sync.refresh(sub)

        assertEquals(listOf(link), world.server.urls)
        assertEquals(2, status.eventCount)
        assertEquals(SUBSCRIPTION_START, status.fetchedAt)
        assertEquals(SUBSCRIPTION_START, status.checkedAt)
        assertNull(status.error)
        assertEquals(status, world.calendar.subscriptionStatus("s1"))
        assertNotNull(world.entry("icsraw:s1"))
        assertEquals(2, world.calendar.subscriptionEvents("s1").first().size)
        // In the calendar with the subscription's name, colour and people.
        val lesson = world.calendar.occurrences(d("2026-10-21"), d("2026-10-21"), world.zone).first().single()
        assertEquals(EventSource.Subscription, lesson.source)
        assertEquals("Skolan", lesson.sourceName)
        assertEquals(listOf("c-ella"), lesson.event.participantIds)
    }

    @Test
    fun aFreshCopyIsUsedAndNotModifiedKeepsTheEvents() = runTest {
        val clock = MutableClock()
        val world = SubscriptionWorld(clock)
        world.server.calendar(link, schoolFeed(), etag = "\"v1\"")
        val sub = world.subscribe("s1", link)
        world.sync.refresh(sub)

        clock.advance(1.hours)
        world.sync.refresh(sub)
        assertEquals(1, world.server.requests.size)

        clock.advance(6.hours)
        world.server.status(link, HttpStatusCode.NotModified)
        val status = world.sync.refresh(sub)

        assertEquals("\"v1\"", world.server.requests.last().headers[HttpHeaders.IfNoneMatch])
        assertEquals(clock.now, status.fetchedAt)
        assertNull(status.error)
        assertEquals(2, world.calendar.subscriptionEvents("s1").first().size)
    }

    @Test
    fun eventsFromAStaleCopyKeepTheTimeOfTheirDownload() = runTest {
        val clock = MutableClock()
        val world = SubscriptionWorld(clock)
        world.server.calendar(link, schoolFeed())
        val sub = world.subscribe("s1", link)
        world.sync.refresh(sub)

        clock.advance(7.hours)
        world.server.status(link, HttpStatusCode.InternalServerError)
        val status = world.sync.refresh(sub)

        assertEquals(SubscriptionError.Http, status.error)
        assertEquals(SUBSCRIPTION_START, status.fetchedAt)
        assertEquals(clock.now, status.checkedAt)
        // Parsed again from the old copy, but not stamped as new (CachedFetcher.store).
        assertEquals(SUBSCRIPTION_START.toEpochMilliseconds(), world.entry(CalendarRepository.subscriptionCacheKey("s1"))?.fetchedAt)
        assertEquals(2, status.eventCount)
    }

    @Test
    fun contentThatIsNotACalendarIsNeverCached() = runTest {
        val clock = MutableClock()
        val world = SubscriptionWorld(clock)
        world.server.calendar(link, "<html><body>Logga in</body></html>")
        val sub = world.subscribe("s1", link)

        val first = world.sync.refresh(sub)

        assertNotNull(first.error)
        assertNull(first.fetchedAt)
        assertEquals(0, first.eventCount)
        assertNull(world.entry("icsraw:s1"))
        assertNull(world.entry("ics:s1"))

        // A good copy, then the school's platform answers with its login page.
        world.server.calendar(link, schoolFeed())
        world.sync.refresh(sub)
        clock.advance(7.hours)
        world.server.calendar(link, "<html><body>Logga in</body></html>")
        val later = world.sync.refresh(sub)

        assertEquals(SubscriptionError.NotCalendar, later.error)
        assertEquals(2, later.eventCount)
        assertTrue(world.entry("icsraw:s1")?.body.orEmpty().startsWith("BEGIN:VCALENDAR"))
    }

    @Test
    fun aNetworkErrorFallsBackToTheCachedEvents() = runTest {
        val clock = MutableClock()
        val world = SubscriptionWorld(clock)
        world.server.calendar(link, schoolFeed())
        val sub = world.subscribe("s1", link)
        world.sync.refresh(sub)

        clock.advance(7.hours)
        world.server.offline(link)
        val status = world.sync.refresh(sub)

        assertEquals(SubscriptionError.Unreachable, status.error)
        assertEquals(SUBSCRIPTION_START, status.fetchedAt)
        val shown = world.calendar.occurrences(d("2026-10-19"), d("2026-11-01"), world.zone).first()
        assertEquals(listOf("Idrott", "Idrott", "Föräldramöte"), shown.map { it.event.title })
    }

    @Test
    fun refreshAllSkipsDisabledSubscriptions() = runTest {
        val world = SubscriptionWorld()
        world.server.calendar(link, schoolFeed())
        val on = world.subscribe("s1", link)
        val off = world.subscribe("s2", "https://klubben.example.se/f15.ics", name = "Klubben", enabled = false)

        val statuses = world.sync.refreshAll(listOf(on, off))

        assertEquals(listOf("s1"), statuses.map { it.subscriptionId })
        assertEquals(listOf(link), world.server.urls)
        assertNull(world.calendar.subscriptionStatus("s2"))
    }

    @Test
    fun anInvalidLinkIsReportedWithoutARequest() = runTest {
        val world = SubscriptionWorld()
        val sub = world.subscribe("s1", "ftp://skolan.example.se/3b.ics")

        val status = world.sync.refresh(sub)

        assertEquals(SubscriptionError.InvalidUrl, status.error)
        assertEquals(emptyList(), world.server.urls)
        assertEquals(status, world.calendar.subscriptionStatus("s1"))
    }

    @Test
    fun aNewLinkDropsTheOldEventsAndAsksTheNewAddressAtOnce() = runTest {
        val clock = MutableClock()
        val world = SubscriptionWorld(clock)
        val other = "https://klubben.example.se/f15.ics"
        world.server.calendar(link, schoolFeed(), etag = "\"school\"")
        world.server.calendar(other, schoolFeed(name = "F15", lesson = "Träning"))
        val sub = world.subscribe("s1", link)
        world.sync.refresh(sub)

        clock.advance(1.hours)
        val moved = sub.copy(url = other)
        world.calendar.saveSubscription(moved)
        world.sync.refresh(moved)

        assertEquals(listOf(link, other), world.server.urls)
        assertNull(world.server.requests.last().headers[HttpHeaders.IfNoneMatch])
        assertEquals(setOf("Träning", "Föräldramöte"), world.calendar.subscriptionEvents("s1").first().map { it.title }.toSet())
    }

    @Test
    fun previewShowsTheNameTheCountAndTheNextThree() = runTest {
        val world = SubscriptionWorld()
        world.server.calendar(link, schoolFeed())

        val result = assertIs<PreviewResult.Found>(world.sync.preview("webcal://skolan.example.se/kalender/3b.ics"))

        assertEquals("Björkängsskolan 3B", result.preview.name)
        assertEquals(2, result.preview.eventCount)
        assertEquals(
            listOf("2026-10-21T13:00", "2026-10-28T13:00", "2026-10-28T18:00").map { LocalDateTime.parse(it) },
            result.preview.upcoming.map { it.start },
        )
        // Nothing is cached before the subscription is saved.
        assertEquals(emptyMap(), world.cache.entries.value)
    }

    @Test
    fun previewSaysWhyItDidNotWork() = runTest {
        val world = SubscriptionWorld()
        world.server.calendar("https://a.example.se/login", "<!doctype html><html></html>")
        world.server.status("https://a.example.se/gone.ics", HttpStatusCode.NotFound)
        world.server.offline("https://a.example.se/offline.ics")

        assertEquals(PreviewResult.Failed(SubscriptionError.NotCalendar), world.sync.preview("https://a.example.se/login"))
        assertEquals(PreviewResult.Failed(SubscriptionError.Http), world.sync.preview("https://a.example.se/gone.ics"))
        assertEquals(PreviewResult.Failed(SubscriptionError.Unreachable), world.sync.preview("https://a.example.se/offline.ics"))
        assertEquals(PreviewResult.Failed(SubscriptionError.InvalidUrl), world.sync.preview("skolan 3b"))
        assertEquals(3, world.server.requests.size)
    }

    @Test
    fun deletingRemovesExactlyItsOwnCacheEntriesAndUndoPutsThemBack() = runTest {
        val world = SubscriptionWorld()
        world.server.calendar(link, schoolFeed())
        world.sync.refresh(world.subscribe("a1", link))
        world.sync.refresh(world.subscribe("a10", link, name = "Klubben"))
        // Keys a LIKE 'ics:a1%' prefix would also have taken.
        world.cache.put(CacheEntryEntity("ics:a1-old", "[]", 0))

        val removed = assertNotNull(world.calendar.deleteSubscription("a1"))

        assertEquals(listOf(listOf("icsraw:a1", "ics:a1", "icsstatus:a1")), world.keys.deleted)
        assertEquals(
            setOf("icsraw:a10", "ics:a10", "icsstatus:a10", "ics:a1-old"),
            world.cache.entries.value.keys,
        )
        assertEquals(listOf("a10"), world.calendar.subscriptions.first().map { it.id })

        world.calendar.restoreSubscription(removed)

        assertEquals(setOf("a1", "a10"), world.calendar.subscriptions.first().map { it.id }.toSet())
        assertEquals(2, world.calendar.subscriptionEvents("a1").first().size)
        assertNotNull(world.calendar.subscriptionStatus("a1"))
        // Back without a download.
        assertEquals(2, world.server.urls.size)
    }

    @Test
    fun failureMessagesMapToKindsWithoutTheHost() {
        assertEquals(SubscriptionError.NotCalendar, SubscriptionSync.classify("Unexpected content from skolan.example.se"))
        assertEquals(SubscriptionError.Http, SubscriptionSync.classify("HTTP 404 from skolan.example.se"))
        assertEquals(SubscriptionError.Unreachable, SubscriptionSync.classify("Unable to resolve host"))
        assertEquals(SubscriptionError.Unreachable, SubscriptionSync.classify(null))
    }

    @Test
    fun linksMustBeWebLinksWithAHostAndAtMost2000Characters() {
        assertEquals("https://cal.example.com/a.ics", SubscriptionSync.normalizeUrl(" webcal://cal.example.com/a.ics "))
        assertEquals("https://cal.example.com/a.ics", SubscriptionSync.normalizeUrl("HTTP://cal.example.com/a.ics"))
        assertNull(SubscriptionSync.normalizeUrl("https://"))
        assertNull(SubscriptionSync.normalizeUrl("https://kalender/a.ics"))
        assertNull(SubscriptionSync.normalizeUrl("https://cal.example.com/a b.ics"))
        val long = "https://cal.example.com/" + "a".repeat(SubscriptionSync.MAX_URL_LENGTH)
        assertNull(SubscriptionSync.normalizeUrl(long))
        assertFalse(SubscriptionSync.looksLikeCalendar("<html>BEGIN</html>"))
    }
}

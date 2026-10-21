package se.pusslet.data.sources

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.pusslet.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The in-process timer: virtual time drives both `delay` and the injected [Clock], and the HTTP engine runs on it too. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRefresherTest {
    private val school = "https://skolan.example.se/3b.ics"
    private val club = "https://klubben.example.se/f15.ics"

    private fun TestScope.world(role: Role = Role.Parent): SubscriptionWorld {
        val clock = object : Clock {
            override fun now(): Instant = SUBSCRIPTION_START + testScheduler.currentTime.milliseconds
        }
        return SubscriptionWorld(clock, role, StandardTestDispatcher(testScheduler)).apply {
            server.calendar(school, schoolFeed())
            server.calendar(club, schoolFeed(name = "F15", lesson = "Träning"))
        }
    }

    private fun SubscriptionWorld.refresher() = SubscriptionRefresher(calendar, sync, sessions, clock)

    @Test
    fun refreshesAtStartAndThenAboutEverySixHours() = runTest {
        val world = world()
        world.subscribe("s1", school)
        world.subscribe("s2", club, enabled = false)

        world.refresher().start(backgroundScope)
        runCurrent()
        assertEquals(listOf(school), world.server.urls)

        advanceTimeBy(6.hours - 1.minutes)
        assertEquals(1, world.server.requests.size)
        advanceTimeBy(2.minutes)
        assertEquals(listOf(school, school), world.server.urls)
        advanceTimeBy(6.hours)
        assertEquals(3, world.server.requests.size)
    }

    @Test
    fun aSubscriptionIsFetchedWhenAddedSwitchedOnOrGivenANewLink() = runTest {
        val world = world()
        val sub = world.subscribe("s1", school)
        world.refresher().start(backgroundScope)
        runCurrent()

        // Added: fetched at once, not at the next six-hour round.
        val added = world.subscribe("s2", club, enabled = true)
        runCurrent()
        assertEquals(listOf(school, club), world.server.urls)

        // A new name or colour needs no download.
        world.calendar.saveSubscription(sub.copy(name = "Ellas klass"))
        runCurrent()
        assertEquals(2, world.server.requests.size)

        // Switched off and on again an hour later: refreshed (the copy is still fresh, so no download).
        advanceTimeBy(1.hours)
        world.calendar.saveSubscription(added.copy(enabled = false))
        runCurrent()
        world.calendar.saveSubscription(added)
        runCurrent()
        assertEquals(currentInstant(), world.calendar.subscriptionStatus("s2")?.checkedAt)
        assertEquals(2, world.server.requests.size)

        // A new link: downloaded at once, even though the old copy is fresh.
        world.calendar.saveSubscription(sub.copy(url = "webcal://klubben.example.se/f15.ics"))
        runCurrent()
        assertEquals(listOf(school, club, club), world.server.urls)
    }

    @Test
    fun aFailedFetchIsTriedAgainAfterAnHour() = runTest {
        val world = world()
        world.server.status(school, HttpStatusCode.ServiceUnavailable)
        world.subscribe("s1", school)
        world.refresher().start(backgroundScope)
        runCurrent()
        assertEquals(SubscriptionError.Unreachable, world.calendar.subscriptionStatus("s1")?.error)

        advanceTimeBy(1.hours - 1.minutes)
        assertEquals(1, world.server.requests.size)
        world.server.calendar(school, schoolFeed())
        advanceTimeBy(2.minutes)
        assertEquals(2, world.server.requests.size)
        assertEquals(null, world.calendar.subscriptionStatus("s1")?.error)
    }

    @Test
    fun caregiversNeverFetchSubscriptions() = runTest {
        val world = world(Role.Caregiver)
        world.subscribe("s1", school)
        val refresher = world.refresher()

        refresher.start(backgroundScope)
        advanceTimeBy(13.hours)

        assertEquals(emptyList(), world.server.urls)
        assertEquals(emptyList(), refresher.refreshAll())
    }

    @Test
    fun theWorkerEntryPointRefreshesEveryEnabledSubscriptionOnce() = runTest {
        val world = world()
        world.subscribe("s1", school)
        world.subscribe("s2", club, enabled = false)
        val refresher = world.refresher()

        val statuses = refresher.refreshAll()
        assertEquals(listOf("s1"), statuses.map { it.subscriptionId })
        assertEquals(listOf(school), world.server.urls)

        // A second worker run within six hours uses the copy; "Uppdatera nu" downloads again.
        refresher.refreshAll()
        assertEquals(1, world.server.requests.size)
        refresher.refreshAllNow()
        assertEquals(2, world.server.requests.size)
    }

    private fun TestScope.currentInstant(): Instant = SUBSCRIPTION_START + testScheduler.currentTime.milliseconds
}

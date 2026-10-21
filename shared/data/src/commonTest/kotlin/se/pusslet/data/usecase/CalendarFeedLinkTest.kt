package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.core.model.Role
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server side of the feed: what the app asked for, and whether the network "works". */
private class FeedServer : FamilyRemote by LocalOnlyFamilyRemote {
    val feeds = HashMap<String, String>()
    var online = true

    override suspend fun createCalendarFeed(token: String, familyId: String, memberId: String?, uid: String) {
        check(online) { "offline" }
        feeds[token] = familyId
    }

    override suspend fun revokeCalendarFeed(token: String) {
        check(online) { "offline" }
        feeds.remove(token)
    }
}

class CalendarFeedLinkTest {
    private val base = "https://europe-north1-pusslet.cloudfunctions.net/familyCalendarFeed"
    private val config = BackendConfig(BackendMode.Firebase, seedDemoData = false, versionName = "1", calendarFeedBase = base)

    private fun parent(familyId: String = "f") = Session(familyId = familyId, memberId = "m-sara", uid = "u-sara", role = Role.Parent)

    @Test
    fun aParentMakesSharesAndStopsTheLink() = runTest {
        val server = FeedServer()
        val sessions = FixedSessionProvider(parent())
        val feeds = CalendarFeedLink(server, PreferencesRepository(FakePreferenceDao()), sessions, config)
        assertNull(feeds.link.first())

        assertTrue(feeds.create())
        val token = server.feeds.keys.single()
        assertEquals(64, token.length, "two random UUIDs, far beyond guessing")
        assertEquals("f", server.feeds[token])
        assertEquals("$base/$token.ics", feeds.link.first())

        sessions.update { parent(familyId = "annan") }
        assertNull(feeds.link.first(), "another family on this phone has no link")
        sessions.update { parent() }

        assertTrue(feeds.revoke())
        assertTrue(server.feeds.isEmpty())
        assertNull(feeds.link.first())
    }

    @Test
    fun withoutNetworkNothingIsSavedAndCaregiversCannot() = runTest {
        val server = FeedServer().apply { online = false }
        val prefs = PreferencesRepository(FakePreferenceDao())
        val feeds = CalendarFeedLink(server, prefs, FixedSessionProvider(parent()), config)
        assertFalse(feeds.create())
        assertNull(feeds.link.first())

        val caregiver = CalendarFeedLink(FeedServer(), prefs, FixedSessionProvider(parent().copy(role = Role.Caregiver)), config)
        assertFalse(caregiver.create())

        val noBackend = CalendarFeedLink(FeedServer(), prefs, FixedSessionProvider(parent()), config.copy(calendarFeedBase = null))
        assertFalse(noBackend.available)
        assertFalse(noBackend.create())
    }
}

package se.pusslet.data.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.core.model.Role
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.CloudFunctions
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class CalendarFeedsTest {
    private class Remote : FamilyRemote by LocalOnlyFamilyRemote {
        override val available = true
        val feeds = mutableMapOf<String, Pair<String, String?>>()

        override suspend fun createCalendarFeed(token: String, familyId: String, memberId: String?, uid: String) {
            feeds[token] = familyId to memberId
        }

        override suspend fun revokeCalendarFeed(token: String) {
            feeds.remove(token)
        }
    }

    private class Functions(val remote: Remote) : CloudFunctions {
        override val available = true

        override suspend fun revokeCalendarFeeds(familyId: String): Int {
            val mine = remote.feeds.filterValues { it.first == familyId }.keys
            mine.forEach { remote.feeds.remove(it) }
            return mine.size
        }

        override suspend fun deleteFamily(familyId: String) = Unit

        override suspend fun deleteAccount() = Unit
    }

    private class Auth(user: AuthUser?) : AuthRepository {
        override val available = true
        override val currentUser: StateFlow<AuthUser?> = MutableStateFlow(user)

        override suspend fun signInAnonymously() = error("unused")

        override suspend fun signInWithEmail(email: String, password: String) = error("unused")

        override suspend fun createAccount(email: String, password: String) = error("unused")

        override suspend fun signInWithGoogle(idToken: String) = error("unused")

        override suspend fun signOut() = Unit
    }

    private class Prefs : PreferenceDao {
        val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observe(key: String) = values.let { flow -> kotlinx.coroutines.flow.flow { flow.collect { emit(it[key]) } } }

        override suspend fun get(key: String) = values.value[key]

        override suspend fun put(entity: PreferenceEntity) {
            values.value = values.value + (entity.key to entity.value)
        }

        override suspend fun remove(key: String) {
            values.value = values.value - key
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }

    private object FixedClock : Clock {
        override fun now() = Instant.parse("2026-10-08T05:10:00Z")
    }

    private val remote = Remote()

    private fun feeds(role: Role = Role.Parent, signedIn: Boolean = true, mode: BackendMode = BackendMode.Firebase) = CalendarFeeds(
        remote = remote,
        functions = Functions(remote),
        auth = Auth(if (signedIn) AuthUser("u-sara", null, null, isAnonymous = false) else null),
        prefs = PreferencesRepository(Prefs()),
        sessions = FixedSessionProvider(Session("f-lind", "m-sara", "u-sara", role)),
        config = BackendConfig(mode, seedDemoData = false, versionName = "test", linkHost = "pusslet.web.app"),
        clock = FixedClock,
    )

    @Test
    fun createsSecretLinksForTheFamilyOrJustMe() = runTest {
        val f = feeds()
        assertEquals(FeedAvailability.Ready, f.availability.first())
        val family = f.create(mine = false)
        val mine = f.create(mine = true)
        assertEquals(64, family.token.length)
        assertTrue(family.token.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(family.token != mine.token)
        assertEquals("f-lind" to null, remote.feeds[family.token])
        assertEquals("f-lind" to "m-sara", remote.feeds[mine.token])
        assertEquals("https://pusslet.web.app/feed/${family.token}.ics", f.url(family))
        assertEquals(listOf(family, mine), f.links.first())

        f.revoke(family)
        assertEquals(setOf(mine.token), remote.feeds.keys)
        assertEquals(listOf(mine), f.links.first())

        assertEquals(1, f.revokeAll())
        assertTrue(remote.feeds.isEmpty())
        assertTrue(f.links.first().isEmpty())
    }

    @Test
    fun demoLinksPointAtTheHostingEmulator() = runTest {
        val f = feeds(mode = BackendMode.Emulator)
        assertEquals("http://10.0.2.2:5000/feed/abc.ics", f.url(FeedLink("abc", null, FixedClock.now())))
    }

    @Test
    fun onlySignedInParentsCanMakeLinks() = runTest {
        assertEquals(FeedAvailability.ParentsOnly, feeds(role = Role.Caregiver).availability.first())
        assertEquals(FeedAvailability.NeedsSignIn, feeds(signedIn = false).availability.first())
    }
}

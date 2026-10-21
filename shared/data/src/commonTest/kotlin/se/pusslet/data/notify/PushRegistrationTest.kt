package se.pusslet.data.notify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `users/{uid}/devices/{deviceId}` as the app writes it. */
private class Devices : FamilyRemote by LocalOnlyFamilyRemote {
    val tokens = LinkedHashMap<String, String>()

    override suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String, locale: String, quietHours: String?) {
        tokens["$uid/$deviceId"] = fcmToken
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationTest {
    @Test
    fun theTokenIsStoredOncePerPhoneWhenAnAccountWithAFamilyIsOnIt() = runTest {
        val sessions = FixedSessionProvider(Session())
        val prefs = PreferencesRepository(FakePreferenceDao())
        val devices = Devices()
        val push = PushRegistration(sessions, prefs, devices, SequentialIdGenerator())
        var token = "t1"
        push.start(backgroundScope, { "sv-SE" }) { token }
        runCurrent()
        assertTrue(devices.tokens.isEmpty(), "no account yet")
        assertFalse(push.register("t0", "sv-SE"))

        sessions.update { Session(familyId = "f", memberId = "m-sara", uid = "u-sara") }
        runCurrent()
        val id = prefs.get(Prefs.DeviceId)
        assertEquals(mapOf("u-sara/$id" to "t1"), devices.tokens)

        token = "t2"
        assertTrue(push.register(token, "sv-SE"), "FCM handed out a new token")
        assertEquals(mapOf("u-sara/$id" to "t2"), devices.tokens, "the same phone, the same document")
    }
}

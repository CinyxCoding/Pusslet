package se.pusslet.data.notify

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider

/**
 * This phone's FCM token in `users/{uid}/devices/{deviceId}` (ADR-042), where the push functions look for it: written
 * when an account with a family is on the phone, and again whenever FCM hands out a new token. The device id is made once
 * and kept in [Prefs.DeviceId], so one phone stays one document however often its token changes. Quiet hours are not
 * sent along: this phone applies them itself ([PushMessages]).
 */
class PushRegistration(
    private val sessions: SessionProvider,
    private val prefs: PreferencesRepository,
    private val remote: FamilyRemote,
    private val ids: IdGenerator,
) {
    private val log = Logger.withTag("Push")

    /** Registers the token from [token] each time a signed-in account with a family comes on the phone. */
    fun start(scope: CoroutineScope, locale: () -> String, token: suspend () -> String?): Job = scope.launch {
        sessions.session.map { s -> s.uid?.takeIf { s.canSync } }.distinctUntilChanged().filterNotNull().collect {
            val current = try {
                token()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.w(e) { "No push token" }
                null
            }
            if (current != null) register(current, locale())
        }
    }

    /** Stores [token] for the signed-in account; false without one (it is stored when [start] sees the account). */
    suspend fun register(token: String, locale: String): Boolean {
        val session = sessions.session.value
        val uid = session.uid?.takeIf { session.canSync } ?: return false
        return try {
            remote.registerDevice(uid, deviceId(), token, locale, quietHours = null)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.w(e) { "Could not register the push token" }
            false
        }
    }

    private suspend fun deviceId(): String = prefs.get(Prefs.DeviceId).ifEmpty { ids.newId().also { prefs.set(Prefs.DeviceId, it) } }
}

package se.pusslet.android.notify

import android.annotation.SuppressLint
import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import se.pusslet.core.model.HandoffKind
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.di.AppScope
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.FamilyAlerts
import se.pusslet.data.usecase.HandoffAlert
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Registers this phone for push under the signed-in account (real Firebase only – the emulator has no FCM).
 * FCM hands the token to [PussletMessagingService.onRegistered]; it is kept on the phone and sent to the backend
 * whenever the account or family changes.
 */
class PushRegistration(
    private val remote: FamilyRemote,
    private val auth: AuthRepository,
    private val sessions: SessionProvider,
    private val prefs: PreferencesRepository,
    private val config: BackendConfig,
) {
    private val log = Logger.withTag("Push")

    fun start(scope: CoroutineScope) {
        if (config.mode != BackendMode.Firebase) return
        scope.launch {
            try {
                FirebaseMessaging.getInstance().register().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "FCM registration failed: ${e::class.simpleName}" }
            }
        }
        scope.launch {
            combine(auth.currentUser, sessions.session) { user, s -> user?.uid to s.familyId }
                .distinctUntilChanged()
                .collect { (uid, familyId) -> if (uid != null && familyId != null) upload(uid) }
        }
    }

    /** A new token from FCM. */
    suspend fun onToken(token: String) {
        prefs.set(Prefs.PushToken, token)
        auth.currentUser.value?.uid?.let { upload(it) }
    }

    private suspend fun upload(uid: String) {
        val token = prefs.get(Prefs.PushToken).ifEmpty { return }
        try {
            val deviceId = prefs.get(Prefs.DeviceId).ifEmpty { Uuid.random().toString().also { prefs.set(Prefs.DeviceId, it) } }
            val quiet = if (prefs.get(Prefs.QuietHoursEnabled)) prefs.get(Prefs.QuietHours).let { "${it.start}-${it.end}" } else null
            remote.registerDevice(uid, deviceId, token, Locale.getDefault().toLanguageTag(), quiet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Push registration failed: ${e::class.simpleName}" }
        }
    }
}

/**
 * Data-only pushes from the backend become the same notifications the app shows while running.
 * Token changes arrive in [onRegistered]: firebase-messaging 25 deprecated onNewToken for it, which Lint does not know yet.
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class PussletMessagingService : FirebaseMessagingService(), KoinComponent {
    private val alerts: FamilyAlerts by inject()

    override fun onRegistered(token: String) {
        get<CoroutineScope>(AppScope).launch { get<PushRegistration>().onToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val date = d["date"]?.take(DATE_LENGTH)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        when (d["type"]) {
            "handoff_request" -> alerts.handoffRequest(alert(d, date))
            "handoff_answer" -> alerts.handoffAnswer(alert(d, date), d["byName"].orEmpty(), d["accepted"] == "true")
            "shopping_added" -> alerts.shoppingAdded(d["listId"].orEmpty(), d["byName"].orEmpty(), d["name"].orEmpty())
            "event_added", "event_changed" ->
                alerts.eventChanged(d["eventId"].orEmpty(), date, d["title"].orEmpty(), d["byName"].orEmpty(), d["type"] == "event_added")
        }
    }

    private fun alert(d: Map<String, String>, date: LocalDate) = HandoffAlert(
        requestId = d["requestId"].orEmpty(),
        fromName = d["fromName"].orEmpty(),
        kind = if (d["kind"] == "dropoff") HandoffKind.DropOff else HandoffKind.PickUp,
        childNames = d["children"].orEmpty().split(", ").filter { it.isNotBlank() },
        eventId = d["eventId"].orEmpty(),
        eventTitle = d["eventTitle"].orEmpty(),
        date = date,
        time = d["time"]?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    )

    private companion object {
        const val DATE_LENGTH = 10
    }
}

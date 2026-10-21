package se.pusslet.android.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext
import se.pusslet.android.notify.AndroidNotifier
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.di.AppScope
import se.pusslet.data.notify.PushMessages
import se.pusslet.data.notify.PushRegistration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Push from other phones (ADR-042): only with a real Firebase project, as the emulators have no FCM. FCM starts switched
 * off (the manifest's `firebase_messaging_auto_init_enabled`) and is switched on here, so no token is made in the other
 * modes. A message is worded by [PushMessages]; one that waits for the quiet hours is a WorkManager job holding the
 * message's ids, which asks [PushMessages] again when it runs.
 */
object Push {
    private val log = Logger.withTag("Push")
    private const val TAG = "pusslet-push"

    /** From `Application.onCreate`. */
    fun start(config: BackendConfig, registration: PushRegistration, scope: CoroutineScope) {
        if (config.mode != BackendMode.Firebase) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        // FCM tokens, as the functions send to them (sendEachForMulticast). firebase-messaging 25 deprecates them for
        // installation ids (register()/onRegistered); moving over needs the server side too (docs/PROGRESS.md).
        @Suppress("DEPRECATION")
        registration.start(scope, ::locale) { messaging.token.await() }
    }

    /** Shows the notification for [data] now, or schedules it for the end of the quiet hours. */
    suspend fun handle(context: Context, data: Map<String, String>, messages: PushMessages, clock: Clock) {
        val planned = messages.planned(data) ?: return
        val wait = planned.at - clock.now()
        if (wait <= SHOW_EARLY) {
            AndroidNotifier(context).show(planned)
            return
        }
        val request = OneTimeWorkRequestBuilder<ShowPushWorker>()
            .setInitialDelay(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(*data.map { (k, v) -> k to v }.toTypedArray()))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("push:${planned.key}", ExistingWorkPolicy.REPLACE, request)
        log.d { "A ${planned.kind} push waits for the quiet hours" }
    }

    fun locale(): String = Locale.getDefault().toLanguageTag()

    /** A job runs a little late rather than early; a minute's difference is not worth another round. */
    private val SHOW_EARLY: Duration = 60.seconds
}

/** Receives FCM's messages and new tokens; data-only messages, so this runs for them in the background too. */
class PussletMessagingService :
    FirebaseMessagingService(),
    KoinComponent {
    @Deprecated("FCM tokens are deprecated for installation ids; see Push.start")
    override fun onNewToken(token: String) {
        if (GlobalContext.getOrNull() == null || get<BackendConfig>().mode != BackendMode.Firebase) return
        get<CoroutineScope>(AppScope).launch { get<PushRegistration>().register(token, Push.locale()) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (GlobalContext.getOrNull() == null) return
        // FCM gives a message a few seconds; the session and Room are local, so this is quick unless the app just started.
        runBlocking {
            withTimeoutOrNull(HANDLE_WITHIN) { Push.handle(applicationContext, message.data, get(), get()) }
        }
    }

    private companion object {
        val HANDLE_WITHIN: Duration = 8.seconds
    }
}

/** A push that waited for the quiet hours: worded again now, so an answer in between leaves it out. */
class ShowPushWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    override suspend fun doWork(): Result {
        val data = inputData.keyValueMap.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        Push.handle(applicationContext, data, get(), get())
        return Result.success()
    }
}

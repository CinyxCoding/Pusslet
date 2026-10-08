package se.pusslet.android.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import se.pusslet.android.R
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.QuietHours
import se.pusslet.data.di.AppScope
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.usecase.FamilyAlerts
import se.pusslet.data.usecase.HandoffAlert
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.ui.resources.*
import kotlin.time.Clock

/**
 * Hand-off requests as notifications with one-tap answers. During quiet hours they still arrive, but silently –
 * a request for tomorrow's pick-up should not wake anyone.
 */
class AndroidFamilyAlerts(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : FamilyAlerts {
    override fun handoffRequest(alert: HandoffAlert) {
        scope.launch {
            val what = getString(
                if (alert.kind ==
                    HandoffKind.PickUp
                ) {
                    Res.string.notify_handoff_request_pickup
                } else {
                    Res.string.notify_handoff_request_dropoff
                },
                alert.childNames.joinToString(", ").ifEmpty { alert.eventTitle },
            )
            val time = alert.time?.let { " ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }.orEmpty()
            Notifications.show(
                context,
                Channel.Handoffs,
                notificationId(alert.requestId),
                NotificationContent(
                    title = getString(Res.string.notify_handoff_request_title, what),
                    text = getString(Res.string.notify_handoff_request_text, alert.fromName, alert.eventTitle, time.trim()),
                    eventId = alert.eventId,
                    occurrenceDate = alert.date,
                    actions = listOf(
                        action(alert.requestId, accept = true, getString(Res.string.notify_handoff_accept)),
                        action(alert.requestId, accept = false, getString(Res.string.notify_handoff_decline)),
                    ),
                    silent = isQuietNow(),
                ),
            )
        }
    }

    override fun handoffAnswer(alert: HandoffAlert, byName: String, accepted: Boolean) {
        scope.launch {
            val text = getString(
                if (accepted) Res.string.notify_handoff_answered_yes else Res.string.notify_handoff_answered_no,
                byName,
                alert.eventTitle,
            )
            Notifications.show(
                context,
                Channel.Handoffs,
                notificationId(alert.requestId),
                NotificationContent(
                    text,
                    alert.childNames.joinToString(", "),
                    eventId = alert.eventId,
                    occurrenceDate = alert.date,
                    silent = isQuietNow(),
                ),
            )
        }
    }

    override fun shoppingAdded(listId: String, byName: String, itemName: String) {
        scope.launch {
            val line = getString(Res.string.notify_shopping_added, byName, itemName)
            val id = "shopping:$listId".hashCode()
            val lines = (previousLines(id) + line).takeLast(MAX_LINES)
            Notifications.show(
                context,
                Channel.Shopping,
                id,
                NotificationContent(
                    title = if (lines.size == 1) line else getString(Res.string.notify_shopping_added_many, byName, lines.size),
                    text = line,
                    lines = if (lines.size > 1) lines else emptyList(),
                    silent = isQuietNow(),
                ),
            )
        }
    }

    override fun eventChanged(eventId: String, date: LocalDate, title: String, byName: String, added: Boolean) {
        scope.launch {
            val line = getString(if (added) Res.string.notify_event_added else Res.string.notify_event_changed, byName, title)
            // One running summary instead of a notification per change ("sammanslagning").
            val id = FAMILY_EVENTS_ID
            val lines = (previousLines(id) + line).distinct().takeLast(MAX_LINES)
            Notifications.show(
                context,
                Channel.Family,
                id,
                NotificationContent(
                    title = if (lines.size == 1) line else getString(Res.string.notify_events_changed_many, lines.size),
                    text = line,
                    lines = if (lines.size > 1) lines else emptyList(),
                    eventId = eventId,
                    occurrenceDate = date,
                    silent = true,
                ),
            )
        }
    }

    /** Lines of a notification that is still showing, so new ones are added to it. */
    private fun previousLines(id: Int): List<String> {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return emptyList()
        val shown = manager.activeNotifications.firstOrNull { it.id == id }?.notification ?: return emptyList()
        val lines = shown.extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.map { it.toString() }
        return lines ?: listOfNotNull(shown.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString())
    }

    private suspend fun isQuietNow(): Boolean {
        if (!prefs.get(Prefs.QuietHoursEnabled)) return false
        val window = prefs.get(Prefs.QuietHours)
        return clock.now().toLocalDateTime(zone).time in QuietHours(window.start, window.end)
    }

    private fun action(requestId: String, accept: Boolean, label: String): NotificationCompat.Action {
        val intent = Intent(context, HandoffAnswerReceiver::class.java)
            .putExtra(HandoffAnswerReceiver.EXTRA_REQUEST, requestId)
            .putExtra(HandoffAnswerReceiver.EXTRA_ACCEPT, accept)
        val pending = PendingIntent.getBroadcast(
            context,
            (requestId + accept).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(if (accept) R.drawable.ic_notification else 0, label, pending)
    }

    companion object {
        private const val MAX_LINES = 6
        private val FAMILY_EVENTS_ID = "family-events".hashCode()

        fun notificationId(requestId: String) = "handoff-request:$requestId".hashCode()
    }
}

/** "Jag tar det" / "Kan inte" straight from the notification, without opening the app. */
class HandoffAnswerReceiver : BroadcastReceiver(), KoinComponent {
    private val requests: HandoffRequests by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        val pending = goAsync()
        get<CoroutineScope>(AppScope).launch {
            try {
                requests.answer(requestId, accept)
                NotificationManagerCompat.from(context).cancel(AndroidFamilyAlerts.notificationId(requestId))
            } catch (e: IllegalStateException) {
                Logger.withTag("Handoff").w { "Answer failed: ${e::class.simpleName}" }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST = "requestId"
        const val EXTRA_ACCEPT = "accept"
    }
}

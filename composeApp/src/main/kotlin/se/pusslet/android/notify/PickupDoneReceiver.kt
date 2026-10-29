package se.pusslet.android.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.data.di.AppScope
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.usecase.PickupReceipts

/**
 * "Hämtad" on a notification (ADR-048): "Du hämtar Ella 15:45" and "Är Ella hämtad?" can be ticked off from the
 * notification, on a locked phone once it is unlocked (Android 12+). The receipt is written like the button on Idag does it,
 * then the notification goes away.
 */
class PickupDoneReceiver :
    BroadcastReceiver(),
    KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT) ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val done = goAsync()
        get<CoroutineScope>(AppScope).launch {
            try {
                val occurrence = get<CalendarRepository>().allOccurrences(date, date, get<TimeZone>()).first()
                    .firstOrNull { it.occurrenceDate == date && (it.event.id == eventId || it.event.seriesId == eventId) }
                if (occurrence != null) get<PickupReceipts>().confirm(occurrence)
                NotificationManagerCompat.from(context).cancel(notificationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.withTag("Pickup").w(e) { "Could not tick off a pick-up from a notification" }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_EVENT = "se.pusslet.pickup.event"
        private const val EXTRA_DATE = "se.pusslet.pickup.date"
        private const val EXTRA_NOTIFICATION = "se.pusslet.pickup.notification"

        fun intent(context: Context, eventId: String, date: LocalDate, notificationId: Int): PendingIntent {
            val intent = Intent(context, PickupDoneReceiver::class.java)
                .putExtra(EXTRA_EVENT, eventId)
                .putExtra(EXTRA_DATE, date.toString())
                .putExtra(EXTRA_NOTIFICATION, notificationId)
            return PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

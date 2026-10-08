package se.pusslet.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import se.pusslet.android.MainActivity
import se.pusslet.android.R
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.notify_channel_family
import se.pusslet.ui.resources.notify_channel_family_desc
import se.pusslet.ui.resources.notify_channel_handoffs
import se.pusslet.ui.resources.notify_channel_handoffs_desc
import se.pusslet.ui.resources.notify_channel_morning
import se.pusslet.ui.resources.notify_channel_morning_desc
import se.pusslet.ui.resources.notify_channel_reminders
import se.pusslet.ui.resources.notify_channel_reminders_desc
import se.pusslet.ui.resources.notify_channel_shopping
import se.pusslet.ui.resources.notify_channel_shopping_desc

/**
 * One channel per kind, so families decide in the system settings what may make a sound
 * (a pick-up nudge yes, "Johan ändrade fotbollen" probably not).
 */
enum class Channel(val id: String, val importance: Int, val title: StringResource, val description: StringResource) {
    Reminders(
        "reminders",
        NotificationManager.IMPORTANCE_HIGH,
        Res.string.notify_channel_reminders,
        Res.string.notify_channel_reminders_desc,
    ),
    Handoffs("handoffs", NotificationManager.IMPORTANCE_HIGH, Res.string.notify_channel_handoffs, Res.string.notify_channel_handoffs_desc),
    Morning("morning", NotificationManager.IMPORTANCE_DEFAULT, Res.string.notify_channel_morning, Res.string.notify_channel_morning_desc),
    Shopping(
        "shopping",
        NotificationManager.IMPORTANCE_DEFAULT,
        Res.string.notify_channel_shopping,
        Res.string.notify_channel_shopping_desc,
    ),
    Family("family", NotificationManager.IMPORTANCE_LOW, Res.string.notify_channel_family, Res.string.notify_channel_family_desc),
}

/** What a notification shows and where a tap leads. */
data class NotificationContent(
    val title: String,
    val text: String,
    val lines: List<String> = emptyList(),
    val eventId: String? = null,
    val occurrenceDate: LocalDate? = null,
    val actions: List<NotificationCompat.Action> = emptyList(),
    /** Notifications of the same group are bundled ("3 ändringar i kalendern"). */
    val group: String? = null,
    /** Quiet hours: shown, but without sound or vibration. */
    val silent: Boolean = false,
)

object Notifications {
    const val EXTRA_EVENT_ID = "se.pusslet.open.eventId"
    const val EXTRA_DATE = "se.pusslet.open.date"

    /** Creates or renames the channels in the current language. Safe to call on every start. */
    suspend fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            Channel.entries.map { c ->
                NotificationChannel(c.id, getString(c.title), c.importance).apply { description = getString(c.description) }
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        // The runtime permission exists from Android 13; before that the switch above is all there is.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun show(context: Context, channel: Channel, id: Int, content: NotificationContent) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            content.eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
            content.occurrenceDate?.let { putExtra(EXTRA_DATE, it.toString()) }
        }
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(if (channel == Channel.Reminders || channel == Channel.Handoffs) NotificationCompat.CATEGORY_REMINDER else null)
        if (content.lines.isNotEmpty()) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) })
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        content.group?.let { builder.setGroup(it) }
        builder.setSilent(content.silent)
        content.actions.forEach(builder::addAction)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the post; nothing to show.
            co.touchlab.kermit.Logger.withTag("Notifications").w { "Notification not permitted: ${e::class.simpleName}" }
        }
    }
}

package se.pusslet.android.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import se.pusslet.android.MainActivity
import se.pusslet.android.R
import se.pusslet.android.platform.useAppLanguage
import se.pusslet.core.notify.NotificationImportance
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.ui.notify.NotificationText
import se.pusslet.ui.notify.NotificationTexts

/**
 * Shows a [PlannedNotification] (ADR-036): one channel per [NotificationKind], the texts from the string resources, and a
 * tap that opens what it is about ([MainActivity] reads [EXTRA_TARGET]). Texts hold children's names, so they are private
 * on the lock screen and never logged.
 */
internal class AndroidNotifier(private val context: Context, private val texts: NotificationTexts = NotificationTexts()) {
    private val log = Logger.withTag("Notifier")
    private val manager = NotificationManagerCompat.from(context)

    suspend fun show(n: PlannedNotification) {
        if (!allowed()) {
            log.i { "Notifications are off; ${n.kind} not shown" }
            return
        }
        useAppLanguage(context)
        ensureChannels()
        val text = texts.text(n)
        val notification = NotificationCompat.Builder(context, n.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(style(text))
            .setContentIntent(tapIntent(n))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(n.kind.groupKey)
            .setCategory(category(n.kind))
            .setWhen(n.at.toEpochMilliseconds())
            .setShowWhen(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(n.notificationId, notification)
        } catch (e: SecurityException) {
            // The permission was taken back between the check and the post.
            log.w(e) { "Could not show a ${n.kind} notification" }
        }
    }

    /** The channels with names in the app's language; creating them again only updates names and descriptions. */
    suspend fun ensureChannels() {
        val channels = NotificationKind.entries.map { kind ->
            val text = texts.channel(kind)
            NotificationChannelCompat.Builder(kind.channelId, kind.importance.toAndroid())
                .setName(text.name)
                .setDescription(text.description)
                .build()
        }
        manager.createNotificationChannelsCompat(channels)
    }

    private fun allowed(): Boolean = manager.areNotificationsEnabled()

    private fun style(text: NotificationText): NotificationCompat.Style = if (text.lines.isEmpty()) {
        NotificationCompat.BigTextStyle().bigText(text.body)
    } else {
        NotificationCompat.InboxStyle().also { style -> text.lines.forEach { style.addLine(it) } }
    }

    private fun tapIntent(n: PlannedNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_TARGET, n.target.encode())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            n.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun category(kind: NotificationKind): String = when (kind) {
        NotificationKind.Reminder, NotificationKind.HandoffSoon, NotificationKind.Morning, NotificationKind.Evening ->
            NotificationCompat.CATEGORY_REMINDER
        NotificationKind.Requests, NotificationKind.Shopping, NotificationKind.Conflicts -> NotificationCompat.CATEGORY_MESSAGE
    }

    private fun NotificationImportance.toAndroid(): Int = when (this) {
        NotificationImportance.Low -> NotificationManagerCompat.IMPORTANCE_LOW
        NotificationImportance.Default -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        NotificationImportance.High -> NotificationManagerCompat.IMPORTANCE_HIGH
    }

    companion object {
        /** A tapped notification; explicit, so no intent filter is needed (and pusslet:// stays for invites). */
        const val ACTION_OPEN = "se.pusslet.action.OPEN_NOTIFICATION"

        /** `NotificationTarget.encode()`. */
        const val EXTRA_TARGET = "se.pusslet.extra.NOTIFICATION_TARGET"
    }
}

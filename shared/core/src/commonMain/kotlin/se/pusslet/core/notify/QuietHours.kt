package se.pusslet.core.notify

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.time.TimeWindow
import se.pusslet.core.time.plusDays
import kotlin.time.Instant

/**
 * Tysta timmar (BRIEF §4.10). What the user asked for at a set time keeps it, even inside the window:
 * - a reminder they set themselves ([PlannedNotification.userTimed]: an event reminder, a to-do with its own time),
 * - [NotificationKind.HandoffSoon] (a child is waiting),
 * - [NotificationKind.Morning] at the time they chose for it,
 * - [NotificationKind.Shopping] (only sent while they shop, ADR-018).
 *
 * Everything else waits until the window ends: to-dos without a time, all-day reminders, requests and conflicts.
 * [NotificationKind.Evening] is dropped instead – "Packa ikväll" the next morning is too late, and Morgonkollen already
 * says what to bring. Windows across midnight (21:00–06:30) end the next morning.
 */
object QuietHours {
    /** Whether a notification keeps its time inside quiet hours. */
    fun passes(kind: NotificationKind, userTimed: Boolean): Boolean = when (kind) {
        NotificationKind.HandoffSoon, NotificationKind.Morning, NotificationKind.Shopping, NotificationKind.Pickups -> true
        NotificationKind.Reminder -> userTimed
        NotificationKind.Evening, NotificationKind.Requests, NotificationKind.Conflicts, NotificationKind.SickDay -> false
    }

    /**
     * When a notification of [kind] planned for [at] should be shown: [at] itself, the end of the quiet window, or null
     * when it should not be shown at all ([NotificationKind.Evening] inside the window).
     */
    fun adjust(
        at: Instant,
        window: TimeWindow,
        enabled: Boolean,
        kind: NotificationKind,
        zone: TimeZone,
        userTimed: Boolean = false,
    ): Instant? {
        if (!enabled || passes(kind, userTimed)) return at
        val local = at.toLocalDateTime(zone)
        if (local.time !in window) return at
        if (kind == NotificationKind.Evening) return null
        return endAfter(local, window, zone)
    }

    /** The end of the window that [local] is in: today when the end is still ahead, else tomorrow. */
    private fun endAfter(local: LocalDateTime, window: TimeWindow, zone: TimeZone): Instant {
        val date = if (local.time < window.end) local.date else local.date.plusDays(1)
        return LocalDateTime(date, window.end).toInstant(zone)
    }

    /** [adjust] for each notification; dropped ones are left out, deferred ones keep their key. */
    fun apply(planned: List<PlannedNotification>, quiet: QuietSettings, zone: TimeZone): List<PlannedNotification> =
        planned.mapNotNull { n ->
            val at = adjust(n.at, quiet.window, quiet.enabled, n.kind, zone, n.userTimed) ?: return@mapNotNull null
            if (at == n.at) n else n.copy(at = at)
        }
}

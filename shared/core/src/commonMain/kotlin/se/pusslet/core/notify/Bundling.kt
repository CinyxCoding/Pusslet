package se.pusslet.core.notify

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Against spam (BRIEF §4.10): notifications of the same kind within [WINDOW] of the first one merge into one summary
 * ("3 påminnelser") at the first one's time. Typical after quiet hours, when the deferred ones all end at 06:30, or when
 * Ella and Simon are picked up at 15:45 and 15:50. Morgonkollen and Kvällskollen come once a day and are never merged.
 *
 * The summary's key is built from its first member's key, so it is stable as long as that member is; its members do
 * not appear on their own, so the Android side cancels their work like any key that is no longer planned.
 */
object Bundling {
    val WINDOW: Duration = 5.minutes

    private val SINGLE = setOf(NotificationKind.Morning, NotificationKind.Evening)

    fun bundle(planned: List<PlannedNotification>): List<PlannedNotification> {
        val sorted = planned.sortedWith(compareBy({ it.at }, { it.key }))
        val (single, merging) = sorted.partition { it.kind in SINGLE || it.payload is NotificationPayload.Bundle }
        val merged = merging.groupBy { it.kind }.flatMap { (kind, list) -> groups(list).map { summary(kind, it) } }
        return (single + merged).sortedWith(compareBy({ it.at }, { it.key }))
    }

    /** Runs of notifications that start within [WINDOW] of the run's first one ([list] is sorted by time). */
    private fun groups(list: List<PlannedNotification>): List<List<PlannedNotification>> {
        val groups = ArrayList<MutableList<PlannedNotification>>()
        for (n in list) {
            val current = groups.lastOrNull()
            if (current != null && n.at - current.first().at <= WINDOW) current += n else groups += mutableListOf(n)
        }
        return groups
    }

    private fun summary(kind: NotificationKind, items: List<PlannedNotification>): PlannedNotification {
        if (items.size == 1) return items.single()
        val first = items.first()
        return PlannedNotification(
            key = "bundle:${kind.name}:${first.key}",
            at = first.at,
            kind = kind,
            payload = NotificationPayload.Bundle(kind, items),
            userTimed = items.any { it.userTimed },
        )
    }
}

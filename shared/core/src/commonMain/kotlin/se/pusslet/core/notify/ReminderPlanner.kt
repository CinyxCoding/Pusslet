package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.plusDays
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A notification this phone should show at [at]. [key] is stable, so re-planning replaces instead of duplicating. */
sealed interface PlannedNotification {
    val key: String
    val at: Instant

    /** "Fotboll börjar 17:30" – the reminder set on the event. */
    data class EventReminder(
        override val key: String,
        override val at: Instant,
        val eventId: String,
        val occurrenceDate: LocalDate,
        val title: String,
        val start: LocalDateTime,
    ) : PlannedNotification

    /** "Dags att hämta Simon 16:00" for whoever drops off or picks up. */
    data class Handoff(
        override val key: String,
        override val at: Instant,
        val eventId: String,
        val occurrenceDate: LocalDate,
        val kind: HandoffKind,
        val time: LocalTime,
        val childIds: List<String>,
    ) : PlannedNotification

    data class TaskDue(
        override val key: String,
        override val at: Instant,
        val taskId: String,
        val title: String,
        val date: LocalDate,
    ) : PlannedNotification

    /** "Morgonkollen": built from the day's plan when it fires. */
    data class Morning(override val key: String, override val at: Instant, val date: LocalDate) : PlannedNotification
}

/** "Tysta timmar" (e.g. 21:00–06:30): crosses midnight when start is after end. */
data class QuietHours(val start: LocalTime, val end: LocalTime) {
    operator fun contains(t: LocalTime): Boolean = if (start <= end) t >= start && t < end else t >= start || t < end

    /** When something that can wait should be shown: [at] itself, or the end of the quiet period it falls in. */
    fun deferred(at: Instant, zone: TimeZone): Instant {
        val local = at.toLocalDateTime(zone)
        if (local.time !in this) return at
        val endDate = if (start > end && local.time >= start) local.date.plusDays(1) else local.date
        return LocalDateTime(endDate, end).toInstant(zone)
    }
}

data class ReminderSettings(
    val morningEnabled: Boolean = false,
    val morningTime: LocalTime = LocalTime(6, 45),
    /** Null = quiet hours off. */
    val quietHours: QuietHours? = QuietHours(LocalTime(21, 0), LocalTime(6, 30)),
)

/**
 * Everything this phone should remind its owner of in the coming hours. Rule based:
 * - an event's own reminder goes to the adults it concerns (taking part, dropping off or picking up), or to all
 *   parents when no adult is named;
 * - whoever drops off or picks up gets "dags att lämna/hämta" [handoffLead] before;
 * - tasks with a time go to whoever is responsible (rotations resolved) and wait for quiet hours to end;
 * - the morning brief at its time.
 * Reminders the user asked for are never silenced by quiet hours.
 */
object ReminderPlanner {
    val handoffLead: Duration = 15.minutes
    val horizon: Duration = 48.hours

    fun plan(
        me: String,
        adultIds: Set<String>,
        occurrences: List<EventOccurrence>,
        tasks: List<FamilyTask>,
        settings: ReminderSettings,
        now: Instant,
        zone: TimeZone,
    ): List<PlannedNotification> {
        val until = now + horizon
        fun relevant(at: Instant) = at > now && at <= until
        val result = mutableListOf<PlannedNotification>()

        for (o in occurrences.filter { it.source == EventSource.Family }) {
            val e = o.event
            val adultsInvolved = (e.participantIds.filter { it in adultIds } + listOfNotNull(e.dropOff?.personId, e.pickUp?.personId)).toSet()
            val concernsMe = me in adultsInvolved || adultsInvolved.isEmpty()
            if (concernsMe && !o.allDay) {
                val start = o.start.toInstant(zone)
                e.reminders.distinct().forEach { minutes ->
                    val at = start - minutes.minutes
                    if (relevant(at)) {
                        result += PlannedNotification.EventReminder("event:${o.key}:$minutes", at, e.id, o.occurrenceDate, e.title, o.start)
                    }
                }
            }
            val children = e.participantIds.filter { it !in adultIds }
            listOf(HandoffKind.DropOff to e.dropOff?.personId, HandoffKind.PickUp to e.pickUp?.personId).forEach { (kind, who) ->
                if (who != me || o.allDay) return@forEach
                val time = if (kind == HandoffKind.DropOff) o.dropOffTime() else o.pickUpTime()
                time ?: return@forEach
                val day = if (kind == HandoffKind.PickUp) o.end.date else o.start.date
                val at = LocalDateTime(day, time).toInstant(zone) - handoffLead
                if (relevant(at)) {
                    result += PlannedNotification.Handoff("handoff:${o.key}:${kind.name}", at, e.id, o.occurrenceDate, kind, time, children)
                }
            }
        }

        val today = now.toLocalDateTime(zone).date
        for (t in tasks.filter { it.dueTime != null }) {
            val time = t.dueTime ?: continue
            TaskScheduler.occurrences(t, today, today.plusDays(2)).forEach { occ ->
                val date = occ.date ?: return@forEach
                if (occ.done || occ.assigneeId != me) return@forEach
                val due = LocalDateTime(date, time).toInstant(zone)
                val at = settings.quietHours?.deferred(due, zone) ?: due
                if (relevant(at)) result += PlannedNotification.TaskDue("task:${t.id}:$date", at, t.id, t.title, date)
            }
        }

        if (settings.morningEnabled) {
            listOf(today, today.plusDays(1)).forEach { d ->
                val at = LocalDateTime(d, settings.morningTime).toInstant(zone)
                if (relevant(at)) result += PlannedNotification.Morning("morning:$d", at, d)
            }
        }
        return result.sortedBy { it.at }
    }
}

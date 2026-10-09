package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.party.PartyRules
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.plusDays
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A notification this phone should show at [at]. [key] is stable, so re-planning replaces instead of duplicating. */
@Serializable
sealed interface PlannedNotification {
    val key: String
    val at: Instant

    /** "Fotboll börjar 17:30" – the reminder set on the event. */
    @Serializable
    @SerialName("eventreminder")
    data class EventReminder(
        override val key: String,
        override val at: Instant,
        val eventId: String,
        val occurrenceDate: LocalDate,
        val title: String,
        val start: LocalDateTime,
    ) : PlannedNotification

    /** "Dags att hämta Simon 16:00" for whoever drops off or picks up. */
    @Serializable
    @SerialName("handoff")
    data class Handoff(
        override val key: String,
        override val at: Instant,
        val eventId: String,
        val occurrenceDate: LocalDate,
        val kind: HandoffKind,
        val time: LocalTime,
        val childIds: List<String>,
    ) : PlannedNotification

    @Serializable
    @SerialName("taskdue")
    data class TaskDue(
        override val key: String,
        override val at: Instant,
        val taskId: String,
        val title: String,
        val date: LocalDate,
    ) : PlannedNotification

    /** "Morgonkollen": built from the day's plan when it fires. */
    @Serializable
    @SerialName("morning")
    data class Morning(override val key: String, override val at: Instant, val date: LocalDate) : PlannedNotification

    /** "Present till Kalas hos Vera" three days before, or "Dags att OSA" the day before the deadline. */
    @Serializable
    @SerialName("party")
    data class Party(
        override val key: String,
        override val at: Instant,
        val eventId: String,
        val occurrenceDate: LocalDate,
        val title: String,
        val partyDate: LocalDate,
        val kind: PartyReminder,
    ) : PlannedNotification
}

@Serializable
enum class PartyReminder { Gift, Rsvp }

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
 * - the morning brief at its time;
 * - parents: a kalas present not yet bought three days before, and an unanswered OSA the evening before its deadline.
 * Reminders the user asked for are never silenced by quiet hours.
 */
object ReminderPlanner {
    val handoffLead: Duration = 15.minutes
    val horizon: Duration = 48.hours

    /** Party reminders come in the early evening, when there is time to sort it out. */
    val partyTime: LocalTime = LocalTime(18, 0)

    fun plan(
        me: String,
        adultIds: Set<String>,
        occurrences: List<EventOccurrence>,
        tasks: List<FamilyTask>,
        settings: ReminderSettings,
        now: Instant,
        zone: TimeZone,
        iAmParent: Boolean = true,
    ): List<PlannedNotification> {
        val window = Window(now, now + horizon, zone)
        val family = occurrences.filter { it.source == EventSource.Family && !it.allDay }
        val parties = if (iAmParent) partyReminders(occurrences.filter { it.source == EventSource.Family }, tasks, window) else emptyList()
        return (
            family.flatMap { eventReminders(it, me, adultIds, window) } +
                family.flatMap { handoffs(it, me, adultIds, window) } +
                taskReminders(tasks, me, settings.quietHours, window) +
                morning(settings, window) +
                parties
        ).sortedBy { it.at }
    }

    private fun partyReminders(occurrences: List<EventOccurrence>, tasks: List<FamilyTask>, w: Window): List<PlannedNotification> =
        occurrences.filter { PartyRules.isParty(it.event) }
            .distinctBy { it.event.seriesId ?: it.event.id }
            .flatMap { o ->
                val gift = PartyRules.giftReminderDay(o, w.today)?.takeIf { PartyRules.needsGift(o, tasks, w.today) }
                val rsvp = PartyRules.details(o.event).rsvpBy?.plusDays(-1)?.takeIf { PartyRules.needsRsvp(o.event, w.today) }
                listOfNotNull(
                    gift?.let { partyAt(o, it, PartyReminder.Gift, w) },
                    rsvp?.let { partyAt(o, it, PartyReminder.Rsvp, w) },
                )
            }

    private fun partyAt(o: EventOccurrence, day: LocalDate, kind: PartyReminder, w: Window): PlannedNotification? {
        val at = LocalDateTime(day, partyTime).toInstant(w.zone)
        if (!w.contains(at)) return null
        val key = "party:${kind.name.lowercase()}:${o.event.seriesId ?: o.event.id}"
        return PlannedNotification.Party(key, at, o.event.id, o.occurrenceDate, o.event.title, o.start.date, kind)
    }

    private class Window(val now: Instant, val until: Instant, val zone: TimeZone) {
        val today: LocalDate = now.toLocalDateTime(zone).date

        fun contains(at: Instant) = at > now && at <= until
    }

    /** The event's own reminders, for the adults it concerns (all parents when no adult is named). */
    private fun eventReminders(o: EventOccurrence, me: String, adultIds: Set<String>, w: Window): List<PlannedNotification> {
        val e = o.event
        val involved = (e.participantIds.filter { it in adultIds } + listOfNotNull(e.dropOff?.personId, e.pickUp?.personId)).toSet()
        if (involved.isNotEmpty() && me !in involved) return emptyList()
        val start = o.start.toInstant(w.zone)
        return e.reminders.distinct().mapNotNull { minutes ->
            val at = start - minutes.minutes
            if (w.contains(
                    at,
                )
            ) {
                PlannedNotification.EventReminder("event:${o.key}:$minutes", at, e.id, o.occurrenceDate, e.title, o.start)
            } else {
                null
            }
        }
    }

    /** "Dags att lämna/hämta" for whoever is assigned, [handoffLead] before. */
    private fun handoffs(o: EventOccurrence, me: String, adultIds: Set<String>, w: Window): List<PlannedNotification> {
        val e = o.event
        val children = e.participantIds.filter { it !in adultIds }
        return listOf(HandoffKind.DropOff to e.dropOff?.personId, HandoffKind.PickUp to e.pickUp?.personId).mapNotNull { (kind, who) ->
            val time =
                (if (kind == HandoffKind.DropOff) o.dropOffTime() else o.pickUpTime())?.takeIf { who == me } ?: return@mapNotNull null
            val day = if (kind == HandoffKind.PickUp) o.end.date else o.start.date
            val at = LocalDateTime(day, time).toInstant(w.zone) - handoffLead
            if (w.contains(
                    at,
                )
            ) {
                PlannedNotification.Handoff("handoff:${o.key}:${kind.name}", at, e.id, o.occurrenceDate, kind, time, children)
            } else {
                null
            }
        }
    }

    /** Tasks with a time, for whoever is responsible that day; they can wait until quiet hours end. */
    private fun taskReminders(tasks: List<FamilyTask>, me: String, quiet: QuietHours?, w: Window): List<PlannedNotification> =
        tasks.flatMap { t ->
            val time = t.dueTime ?: return@flatMap emptyList()
            TaskScheduler.occurrences(t, w.today, w.today.plusDays(2)).mapNotNull { occ ->
                val date = occ.date?.takeIf { !occ.done && occ.assigneeId == me } ?: return@mapNotNull null
                val due = LocalDateTime(date, time).toInstant(w.zone)
                val at = quiet?.deferred(due, w.zone) ?: due
                if (w.contains(at)) PlannedNotification.TaskDue("task:${t.id}:$date", at, t.id, t.title, date) else null
            }
        }

    private fun morning(settings: ReminderSettings, w: Window): List<PlannedNotification> {
        if (!settings.morningEnabled) return emptyList()
        return listOf(w.today, w.today.plusDays(1)).mapNotNull { d ->
            val at = LocalDateTime(d, settings.morningTime).toInstant(w.zone)
            if (w.contains(at)) PlannedNotification.Morning("morning:$d", at, d) else null
        }
    }
}

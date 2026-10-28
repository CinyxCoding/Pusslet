package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.today.Greeting
import kotlin.time.Instant

/**
 * One local notification to show at [at]. [key] is stable and unique: the same reminder gets the same key on every
 * planning run, so the Android side can enqueue unique work under it (REPLACE when the time moved) and cancel the keys
 * that are no longer planned. Payloads hold names for the text, so they stay in memory and are never logged.
 */
data class PlannedNotification(
    val key: String,
    val at: Instant,
    val kind: NotificationKind,
    val payload: NotificationPayload,
    /** The user chose this moment themselves (a reminder they set, a to-do time they typed); see [QuietHours]. */
    val userTimed: Boolean = false,
) {
    /** Where a tap leads. */
    val target: NotificationTarget get() = payload.target

    /** A stable id for `NotificationManager.notify` (the same on every platform and run, unlike a hash map's order). */
    val notificationId: Int get() = key.fold(HASH_SEED) { h, c -> HASH_PRIME * h + c.code }

    private companion object {
        const val HASH_SEED = 17
        const val HASH_PRIME = 31
    }
}

/** What the notification is about, as data; the texts are built from it in shared/ui (`NotificationTexts`). */
sealed interface NotificationPayload {
    val target: NotificationTarget

    /** A reminder the user set on an event ("Tandläkare Simon om 30 min"). [children] are the children it is about. */
    data class EventReminder(
        val eventId: String,
        val occurrenceDate: LocalDate,
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean,
        /** Minutes before the start; for an all-day event the reminder comes the evening before instead. */
        val minutesBefore: Int,
        val location: String,
        val children: List<String>,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /** "Du hämtar Ella 15:45 · Björkängsskolan": my own drop-off or pick-up is soon. */
    data class Handoff(
        val eventId: String,
        val occurrenceDate: LocalDate,
        val kind: HandoffKind,
        val time: LocalTime,
        val children: List<String>,
        /** The event's place, else the child's school for a school block, else the event's title. */
        val place: String,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /**
     * Push from another phone (ADR-042): [from] asks whoever reads it to drop off or pick up [children] at [time]
     * ("Kan du hämta Ella?" · "Sara frågar · 15:45"). [from] is null when the sender is not on this phone yet.
     */
    data class Request(
        val eventId: String,
        val occurrenceDate: LocalDate,
        val kind: HandoffKind,
        val from: String?,
        val children: List<String>,
        val time: LocalTime?,
        val title: String,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /** Push (ADR-042): [by] answered my request, yes ("Bengt hämtar Ella") or no ("Bengt kan inte"). */
    data class Answer(
        val eventId: String,
        val occurrenceDate: LocalDate,
        val kind: HandoffKind,
        val by: String?,
        val accepted: Boolean,
        val children: List<String>,
        val title: String,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /**
     * Push (ADR-047): [child] is home sick on [date] ("Ella är sjuk idag"); [by] reported it and [homeWith] stays home
     * (null = not decided yet: "Vem stannar hemma?").
     */
    data class Sick(val child: String, val date: LocalDate, val tomorrow: Boolean, val by: String?, val homeWith: String?) :
        NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** Push (ADR-048): "Ella är hämtad" · "Bengt · 15:52". */
    data class PickedUp(val children: List<String>, val by: String?, val time: LocalTime?) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** "Är Ella hämtad?" · "Bengt skulle hämta 15:45" [Pickups.GRACE] after a pick-up nobody ticked off (ADR-048). */
    data class PickupUnconfirmed(
        val eventId: String,
        val occurrenceDate: LocalDate,
        val children: List<String>,
        val by: String?,
        val isMe: Boolean,
        val time: LocalTime,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /** "Anmäl frånvaro för Ella" · "Till Björkängsskolan", until it is ticked on Idag (ADR-047). */
    data class SickAbsence(val child: String, val school: String?) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** "Dags för Kåvepenin till Ella" · "5 ml": a planned dose of a course (ADR-050). */
    data class MedicineDose(val child: String, val medicine: String, val dose: String) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** "Present till Kalas hos Vera" · "Kalaset är på lördag – presenten är inte köpt än." (ADR-054). */
    data class PartyGift(val eventId: String, val occurrenceDate: LocalDate, val title: String, val partyDate: LocalDate) :
        NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /** "Dags att OSA: Kalas hos Vera" · "Sista dagen att svara är imorgon." (ADR-054). */
    data class PartyRsvp(val eventId: String, val occurrenceDate: LocalDate, val title: String) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Occurrence(eventId, occurrenceDate)
    }

    /** "Anmäl lovomsorg: Höstlov" · "Senast fre 9 okt" (ADR-051). */
    data class BreakDeadline(val title: String, val deadline: LocalDate) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** "Anmäl VAB för Ella" the evening the child is well again, to the parent who took it (ADR-047). */
    data class VabReport(val child: String, val days: Double) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** Push (ADR-042, ADR-018): [by] added [item] to the list I am shopping from ("Johan lade till mjölk"). */
    data class ShoppingAdded(val by: String?, val item: String) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Shopping
    }

    /** A to-do on its day; [time] is the task's own time (null = the default morning time). */
    data class Task(val taskId: String, val date: LocalDate, val title: String, val time: LocalTime?) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Task(taskId)
    }

    /** Morgonkollen: "God morgon. Johan lämnar Simon 07:50, du hämtar 15:30. Ska med: gympapåse (Ella)." */
    data class Morning(
        val date: LocalDate,
        val greeting: Greeting,
        val handoffs: List<BriefHandoff>,
        val packing: List<BriefPacking>,
        /** The day's first event from the brief's time; only used when there is no hand-off or packing to tell. */
        val first: BriefEvent? = null,
    ) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** Kvällskollen: "Packa ikväll: gympapåse (Ella)". [date] is tomorrow, the day the things are for. */
    data class Evening(val date: LocalDate, val packing: List<BriefPacking>) : NotificationPayload {
        override val target: NotificationTarget get() = NotificationTarget.Today
    }

    /** Several notifications of one kind at nearly the same time, as one summary ("3 påminnelser"); see [Bundling]. */
    data class Bundle(val kind: NotificationKind, val items: List<PlannedNotification>) : NotificationPayload {
        override val target: NotificationTarget
            get() = items.map { it.target }.distinct().singleOrNull() ?: NotificationTarget.Today
    }
}

/** "Johan lämnar Simon 07:50 och Ella 08:05" as data ([se.pusslet.core.today.HandoffLine] without the occurrences). */
data class BriefHandoff(
    val kind: HandoffKind,
    /** Null when the assignee has left the family. */
    val person: String?,
    val isMe: Boolean,
    val stops: List<BriefStop>,
)

data class BriefStop(val child: String, val time: LocalTime)

/** What one child still has to bring: "gympapåse, handduk (Ella)". */
data class BriefPacking(val child: String, val items: List<String>)

data class BriefEvent(val title: String, val time: LocalTime?)

/**
 * Where a tap on a notification leads, as data the Android side can put in an Intent extra ([encode]) and the shell can
 * read back ([decode]) into a route: Idag, an occurrence's details, a to-do or the shopping list.
 */
sealed interface NotificationTarget {
    data object Today : NotificationTarget

    data class Occurrence(val eventId: String, val date: LocalDate) : NotificationTarget

    data class Task(val taskId: String) : NotificationTarget

    data object Shopping : NotificationTarget

    /** "today", "event/<id>/2026-10-21", "task/<id>" or "shopping"; ids are escaped, so any id survives. */
    fun encode(): String = when (this) {
        Today -> TODAY
        Shopping -> SHOPPING
        is Occurrence -> "$EVENT/${escape(eventId)}/$date"
        is Task -> "$TASK/${escape(taskId)}"
    }

    companion object {
        private const val TODAY = "today"
        private const val SHOPPING = "shopping"
        private const val EVENT = "event"
        private const val TASK = "task"

        /** The target [encode] wrote, or null for anything else (an old or broken extra opens nothing). */
        fun decode(value: String?): NotificationTarget? {
            val parts = value?.split('/') ?: return null
            return when (parts.first()) {
                TODAY -> Today.takeIf { parts.size == 1 }
                SHOPPING -> Shopping.takeIf { parts.size == 1 }
                EVENT -> if (parts.size == 3) occurrence(parts[1], parts[2]) else null
                TASK -> if (parts.size == 2) unescape(parts[1])?.takeIf { it.isNotEmpty() }?.let { Task(it) } else null
                else -> null
            }
        }

        private fun occurrence(id: String, date: String): Occurrence? {
            val eventId = unescape(id)?.takeIf { it.isNotEmpty() } ?: return null
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            return Occurrence(eventId, day)
        }

        private fun escape(id: String): String = id.replace("%", "%25").replace("/", "%2F")

        /** Only the two escapes [escape] writes; anything else with a % is not ours. */
        private fun unescape(id: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < id.length) {
                val c = id[i]
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                when (id.substring(i, minOf(i + ESCAPE_LENGTH, id.length)).uppercase()) {
                    "%25" -> out.append('%')
                    "%2F" -> out.append('/')
                    else -> return null
                }
                i += ESCAPE_LENGTH
            }
            return out.toString()
        }

        private const val ESCAPE_LENGTH = 3
    }
}

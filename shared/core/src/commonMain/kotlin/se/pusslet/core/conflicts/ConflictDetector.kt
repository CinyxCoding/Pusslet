package se.pusslet.core.conflicts

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role

sealed interface Conflict {
    val date: LocalDate
    val occurrence: EventOccurrence

    /** A child event needs a pick-up/drop-off but nobody is assigned. */
    data class Unassigned(
        override val date: LocalDate,
        override val occurrence: EventOccurrence,
        val kind: HandoffKind,
        val time: LocalTime,
        val childIds: List<String>,
    ) : Conflict

    /** The assigned adult is busy at that time ("Johan har möte 16:30 men ska hämta Ella"). */
    data class AssigneeBusy(
        override val date: LocalDate,
        override val occurrence: EventOccurrence,
        val kind: HandoffKind,
        val time: LocalTime,
        val personId: String,
        val busyWith: EventOccurrence,
        val childIds: List<String>,
    ) : Conflict

    /** Every parent is busy ("Både Sara och Johan har möte 16:30 – ingen kan hämta Ella"). */
    data class NobodyAvailable(
        override val date: LocalDate,
        override val occurrence: EventOccurrence,
        val kind: HandoffKind,
        val time: LocalTime,
        val busyAdultIds: List<String>,
        val childIds: List<String>,
    ) : Conflict

    /** A child is in two overlapping activities. */
    data class DoubleBooked(
        override val date: LocalDate,
        override val occurrence: EventOccurrence,
        val other: EventOccurrence,
        val childId: String,
    ) : Conflict
}

/**
 * Finds practical clashes for a day. An adult is "busy" when an occurrence they take part in (their own events or
 * their device calendar, mapped to their id) overlaps the hand-off moment ± [slackMinutes].
 */
object ConflictDetector {
    fun detect(
        date: LocalDate,
        occurrences: List<EventOccurrence>,
        people: List<FamilyPerson>,
        slackMinutes: Int = 15,
    ): List<Conflict> {
        val children = people.filter { it.isChild }.map { it.id }.toSet()
        val parents = people.filter { !it.isChild && it.role == Role.Parent }.map { it.id }
        val today = occurrences.filter { it.overlapsDate(date) }
        val result = ArrayList<Conflict>()

        for (o in today) {
            val kids = o.event.participantIds.filter { it in children }
            if (kids.isEmpty() || o.allDay) continue
            for ((kind, handoff) in listOf(HandoffKind.DropOff to o.event.dropOff, HandoffKind.PickUp to o.event.pickUp)) {
                if (handoff == null) continue
                val time = handoff.time ?: if (kind == HandoffKind.PickUp) o.end.time else o.start.time
                val at = LocalDateTime(date, time)
                val busy = today.filter { other -> other !== o && overlaps(other, at, slackMinutes) && other.event.participantIds.none { it in kids } }
                fun busyWith(personId: String) = busy.firstOrNull { personId in it.event.participantIds }
                val assigned = handoff.personId
                if (assigned == null) {
                    val busyParents = parents.filter { busyWith(it) != null }
                    result += if (parents.isNotEmpty() && busyParents.size == parents.size) {
                        Conflict.NobodyAvailable(date, o, kind, time, busyParents, kids)
                    } else {
                        Conflict.Unassigned(date, o, kind, time, kids)
                    }
                } else {
                    busyWith(assigned)?.let { result += Conflict.AssigneeBusy(date, o, kind, time, assigned, it, kids) }
                }
            }
        }

        // Children in two activities at once.
        for (child in children) {
            val mine = today.filter { child in it.event.participantIds && !it.allDay }.sortedBy { it.start }
            for (i in mine.indices) for (j in i + 1 until mine.size) {
                if (mine[i].end > mine[j].start && mine[i].event.id != mine[j].event.id) {
                    result += Conflict.DoubleBooked(date, mine[i], mine[j], child)
                }
            }
        }
        return result
    }

    private fun overlaps(o: EventOccurrence, at: LocalDateTime, slackMinutes: Int): Boolean {
        if (o.allDay) return false
        val minutes = at.hour * 60 + at.minute
        val startDay = o.start.date
        val endDay = o.end.date
        val startMin = if (startDay < at.date) Int.MIN_VALUE else if (startDay > at.date) Int.MAX_VALUE else o.start.hour * 60 + o.start.minute
        val endMin = if (endDay > at.date) Int.MAX_VALUE else if (endDay < at.date) Int.MIN_VALUE else o.end.hour * 60 + o.end.minute
        return startMin < minutes + slackMinutes && endMin > minutes - slackMinutes
    }
}

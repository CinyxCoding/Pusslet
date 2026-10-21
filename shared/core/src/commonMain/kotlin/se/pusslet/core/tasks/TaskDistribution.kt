package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import kotlin.time.Instant

/** What "Så har vi delat" counts, each on its own (they are different things and are never added up). */
enum class ShareKind {
    /** Ticked-off tasks (not chores). */
    Tasks,

    /** Ticked-off chores, one per occurrence. */
    Chores,

    /** Drop-offs and pick-ups as planned on each occurrence. Who actually went is not recorded, so this is the plan. */
    Handoffs,

    /** "Ja" to a hand-off request ("Kan du hämta Ella?"). */
    Requests,
}

data class AdultShare(val person: FamilyPerson, val counts: Map<ShareKind, Int>) {
    fun count(kind: ShareKind): Int = counts[kind] ?: 0
}

/**
 * "Så har vi delat" (BRIEF §4.7): how tasks, chores, hand-offs and answered requests were shared between the adults in the
 * ISO weeks [fromWeek]..[toWeek] (up to today). For a talk, not for blame: plain counts, nothing is scored or compared.
 */
data class Distribution(
    val fromWeek: IsoWeek,
    val toWeek: IsoWeek,
    val from: LocalDate,
    val to: LocalDate,
    val shares: List<AdultShare>,
    /** Done by someone who is no longer in the family: counted for nobody else. */
    val formerMembers: Map<ShareKind, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = shares.all { s -> ShareKind.entries.all { s.count(it) == 0 } } && formerMembers.values.all { it == 0 }

    /**
     * The rows for one kind: every parent (they share the household, so a 0 is part of the picture) and anyone else only
     * when they did some of it. Empty when nobody did any – an empty block is not shown.
     */
    fun rows(kind: ShareKind): List<AdultShare> {
        if (shares.all { it.count(kind) == 0 }) return emptyList()
        return shares.filter { it.person.role == Role.Parent || it.count(kind) > 0 }
    }

    fun formerCount(): Int = formerMembers.values.sum()
}

object TaskDistribution {
    /** The last four ISO weeks: this week (so far) and the three before it. */
    const val WEEKS = 4

    /**
     * [people] is everyone in the family now (children are never counted; an id that is not among them belongs to someone
     * who has left). [occurrences] are the family's occurrences over the period with the hand-offs in effect (overrides and
     * accepted answers applied). Ticks count on the day they were made ([zone]); a recurring task's tick without a time on
     * its occurrence date.
     */
    fun compute(
        today: LocalDate,
        zone: TimeZone,
        people: List<FamilyPerson>,
        tasks: List<FamilyTask>,
        occurrences: List<EventOccurrence>,
        requests: List<HandoffRequest>,
        weeks: Int = WEEKS,
    ): Distribution {
        val toWeek = today.isoWeek
        val fromWeek = toWeek.plusWeeks(-(weeks - 1).coerceAtLeast(0))
        val from = fromWeek.monday
        val range = from..today
        val counter = Counter(people)

        for (task in tasks) {
            val kind = if (task.kind == TaskKind.Chore) ShareKind.Chores else ShareKind.Tasks
            if (task.rrule != null) {
                for ((key, c) in task.completions) {
                    val date = dayOf(c.doneAt, zone) ?: runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                    if (date in range) counter.add(c.doneBy, kind)
                }
            } else if (task.done) {
                val date = dayOf(task.doneAt, zone) ?: continue
                if (date in range) counter.add(task.doneBy, kind)
            }
        }
        for (o in occurrences) {
            if (o.source != EventSource.Family) continue
            for (kind in HandoffKind.entries) {
                val handoff = if (kind == HandoffKind.PickUp) o.event.pickUp else o.event.dropOff
                if (o.handoffDate(kind) in range) counter.add(handoff?.personId, ShareKind.Handoffs)
            }
        }
        for (r in requests) {
            if (r.status == RequestStatus.Accepted && r.occurrenceDate in range) counter.add(r.respondedBy, ShareKind.Requests)
        }
        return Distribution(
            fromWeek = fromWeek,
            toWeek = toWeek,
            from = from,
            to = today,
            shares = people.filter { !it.isChild }.map { AdultShare(it, counter.of(it.id)) },
            formerMembers = counter.former,
        )
    }

    private fun dayOf(at: Instant?, zone: TimeZone): LocalDate? = at?.toLocalDateTime(zone)?.date

    private class Counter(people: List<FamilyPerson>) {
        private val adults = people.filter { !it.isChild }.mapTo(HashSet()) { it.id }
        private val known = people.mapTo(HashSet()) { it.id }
        private val counts = HashMap<String, MutableMap<ShareKind, Int>>()
        val former = LinkedHashMap<ShareKind, Int>()

        fun add(memberId: String?, kind: ShareKind) {
            when {
                memberId == null -> Unit
                memberId in adults -> counts.getOrPut(memberId) { LinkedHashMap() }.merge(kind)
                // A child's id would only come from odd data; children are not part of the adults' share.
                memberId in known -> Unit
                else -> former.merge(kind)
            }
        }

        fun of(id: String): Map<ShareKind, Int> = counts[id].orEmpty()

        private fun MutableMap<ShareKind, Int>.merge(kind: ShareKind) {
            this[kind] = (this[kind] ?: 0) + 1
        }
    }
}

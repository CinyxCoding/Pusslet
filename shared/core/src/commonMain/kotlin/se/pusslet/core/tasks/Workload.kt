package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.TaskKind
import se.pusslet.core.time.minusDays
import kotlin.time.Instant

/** What one adult has handled in the period. Counts only – no scores, no ranking. */
data class PersonLoad(
    val personId: String,
    val tasks: Int = 0,
    val chores: Int = 0,
    val dropOffs: Int = 0,
    val pickUps: Int = 0,
) {
    val total: Int get() = tasks + chores + dropOffs + pickUps
}

data class WorkloadReport(val from: LocalDate, val to: LocalDate, val people: List<PersonLoad>) {
    val isEmpty: Boolean get() = people.all { it.total == 0 }

    /** Largest single count, for drawing bars on a shared scale. */
    val maxCount: Int get() = people.maxOfOrNull { maxOf(it.tasks, it.chores, it.dropOffs, it.pickUps) } ?: 0
}

/**
 * "Fördelningsvy": how tasks, chores and drop-offs/pick-ups were shared between the adults over the last weeks.
 * Meant for a conversation, not for keeping score – the UI shows plain counts side by side.
 * A hand-off counts for the person assigned to it on a day that has passed; tasks count for whoever ticked them off.
 */
object Workload {
    fun report(
        adultIds: List<String>,
        tasks: List<FamilyTask>,
        occurrences: List<EventOccurrence>,
        today: LocalDate,
        zone: TimeZone,
        weeks: Int = 4,
    ): WorkloadReport {
        val from = today.minusDays(weeks * DAYS_PER_WEEK - 1)
        val loads = adultIds.associateWith { PersonLoad(it) }.toMutableMap()

        fun bump(id: String?, transform: (PersonLoad) -> PersonLoad) {
            val current = id?.let { loads[it] } ?: return
            loads[id] = transform(current)
        }

        fun inPeriod(at: Instant?): Boolean = at?.toLocalDateTime(zone)?.date?.let { it in from..today } ?: false

        for (t in tasks) {
            val chore = t.kind == TaskKind.Chore
            val done = buildList {
                if (t.done && inPeriod(t.doneAt)) add(t.doneBy)
                t.completions.forEach { (date, c) ->
                    val d = runCatching { LocalDate.parse(date) }.getOrNull()
                    if (d != null && d in from..today) add(c.doneBy)
                }
            }
            done.forEach { by -> bump(by) { if (chore) it.copy(chores = it.chores + 1) else it.copy(tasks = it.tasks + 1) } }
        }

        for (o in occurrences) {
            if (o.source != EventSource.Family || o.start.date !in from..today) continue
            bump(o.event.dropOff?.personId) { it.copy(dropOffs = it.dropOffs + 1) }
            bump(o.event.pickUp?.personId) { it.copy(pickUps = it.pickUps + 1) }
        }
        return WorkloadReport(from, today, adultIds.mapNotNull { loads[it] })
    }

    private const val DAYS_PER_WEEK = 7
}

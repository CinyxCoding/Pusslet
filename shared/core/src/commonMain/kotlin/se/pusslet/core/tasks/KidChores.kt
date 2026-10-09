package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Allowance
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.time.Instant

enum class KidChoreStatus { Todo, Waiting, Approved }

data class KidChore(val occurrence: TaskOccurrence, val status: KidChoreStatus) {
    val stars: Int get() = occurrence.task.stars
}

/** A child's "Klar!" that a parent has not approved yet. */
data class PendingApproval(val task: FamilyTask, val key: String, val date: LocalDate?, val childId: String, val doneAt: Instant?) {
    val stars: Int get() = task.stars
}

data class StarWeek(val week: IsoWeek, val approved: Int, val waiting: Int, val amount: Int?)

/**
 * Children's chores, stars and veckopeng (BRIEF §4.7). A child taps "Klar!" (waiting), a parent approves (stars count)
 * or says it is not done. Stars belong to the week of the chore's date (or of the day a one-off task was done).
 */
object KidChores {
    fun status(o: TaskOccurrence): KidChoreStatus {
        if (!o.done) return KidChoreStatus.Todo
        val approved = if (o.task.rrule != null && o.date != null) o.task.completions[o.key]?.approved ?: true else o.task.approved
        return if (approved) KidChoreStatus.Approved else KidChoreStatus.Waiting
    }

    /** What a child sees: today's chores, earlier ones this week not yet done, and one-off tasks still open. */
    fun forChild(childId: String, tasks: List<FamilyTask>, today: LocalDate): List<KidChore> {
        val monday = today.isoWeek.monday
        return tasks.flatMap { t ->
            if (t.rrule != null) {
                TaskScheduler.occurrences(t, monday, today).filter { o -> o.date == today || !o.done }
            } else {
                listOf(TaskOccurrence(t, t.due, t.assigneeId, t.done)).filter { o ->
                    !o.done || (o.task.doneAt != null && !o.task.approved) || o.date == today
                }
            }
        }.filter { it.assigneeId == childId && (it.date == null || it.date <= today.plusDays(WEEK_DAYS)) }
            .map { KidChore(it, status(it)) }
            .sortedWith(compareBy({ it.status == KidChoreStatus.Approved }, { it.occurrence.date }, { it.occurrence.task.title }))
    }

    fun pending(tasks: List<FamilyTask>, childIds: Set<String>, zone: TimeZone): List<PendingApproval> = tasks.flatMap { t ->
        if (t.rrule != null) {
            t.completions.filter { (_, c) -> !c.approved }.mapNotNull { (key, c) ->
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                val child = TaskScheduler.assigneeFor(t, date)?.takeIf { it in childIds } ?: c.doneBy?.takeIf { it in childIds }
                child?.let { PendingApproval(t, key, date, it, c.doneAt) }
            }
        } else {
            val child = t.assigneeId?.takeIf { it in childIds } ?: t.doneBy?.takeIf { it in childIds }
            if (t.done && !t.approved && child != null) {
                listOf(PendingApproval(t, ONCE, t.doneAt?.toLocalDateTime(zone)?.date, child, t.doneAt))
            } else {
                emptyList()
            }
        }
    }.sortedBy { it.doneAt }

    /** Stars in [week] for [childId]: approved ones count towards veckopeng, waiting ones are shown as such. */
    fun week(childId: String, tasks: List<FamilyTask>, week: IsoWeek, allowance: Allowance?, zone: TimeZone): StarWeek {
        var approved = 0
        var waiting = 0
        for (t in tasks.filter { it.stars > 0 }) {
            if (t.rrule != null) {
                t.completions.forEach { (key, c) ->
                    val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
                    if (date.isoWeek != week || TaskScheduler.assigneeFor(t, date) != childId) return@forEach
                    if (c.approved) approved += t.stars else waiting += t.stars
                }
            } else {
                val doneOn = t.doneAt?.toLocalDateTime(zone)?.date
                if (t.done && t.assigneeId == childId && doneOn?.isoWeek == week) {
                    if (t.approved) approved += t.stars else waiting += t.stars
                }
            }
        }
        return StarWeek(week, approved, waiting, allowance?.let { amount(it, approved) })
    }

    fun amount(a: Allowance, approvedStars: Int): Int = a.weekly + a.perStar * approvedStars

    /** Key of a one-off task's completion in [PendingApproval.key]. */
    const val ONCE = "once"
    private const val WEEK_DAYS = 7
}

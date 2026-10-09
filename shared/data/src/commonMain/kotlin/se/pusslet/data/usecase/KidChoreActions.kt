package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import se.pusslet.core.model.Allowance
import se.pusslet.core.model.Completion
import se.pusslet.core.tasks.KidChores
import se.pusslet.core.tasks.PendingApproval
import se.pusslet.core.tasks.TaskOccurrence
import se.pusslet.core.time.IsoWeek
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock

/** "Klar!" from barnläge, and a parent's answer to it (BRIEF §4.7). */
class KidChoreActions(private val tasks: TaskRepository, private val clock: Clock) {
    /** The child says it is done; stars wait for a parent. */
    suspend fun childDone(o: TaskOccurrence, childId: String) {
        val now = clock.now()
        tasks.update(o.task.id) { t ->
            if (t.rrule != null && o.date != null) {
                t.copy(completions = t.completions + (o.key to Completion(childId, now, approved = false)))
            } else {
                t.copy(done = true, doneAt = now, doneBy = childId, approved = false)
            }
        }
    }

    /** Pressed by mistake: back to not done (only before a parent has approved). */
    suspend fun childUndo(o: TaskOccurrence) {
        tasks.update(o.task.id) { t ->
            if (t.rrule != null && o.date != null) {
                val c = t.completions[o.key]
                if (c == null || c.approved) t else t.copy(completions = t.completions - o.key)
            } else {
                if (t.approved) t else t.copy(done = false, doneAt = null, doneBy = null, approved = true)
            }
        }
    }

    suspend fun approve(p: PendingApproval) {
        tasks.update(p.task.id) { t ->
            if (p.key == KidChores.ONCE) {
                t.copy(approved = true)
            } else {
                val c = t.completions[p.key] ?: return@update t
                t.copy(completions = t.completions + (p.key to c.copy(approved = true)))
            }
        }
    }

    /** "Inte klart än": the chore goes back on the child's list. */
    suspend fun reject(p: PendingApproval) {
        tasks.update(p.task.id) { t ->
            if (p.key == KidChores.ONCE) {
                t.copy(done = false, doneAt = null, doneBy = null, approved = true)
            } else {
                t.copy(completions = t.completions - p.key)
            }
        }
    }
}

/** Veckopeng per child. */
class AllowanceRepository(private val docs: DocumentStore, private val clock: Clock) {
    val allowances: Flow<List<Allowance>> = docs.observeAll(Collections.Allowances, Allowance.serializer())

    suspend fun save(a: Allowance) = docs.put(Collections.Allowances, a.childId, a, Allowance.serializer())

    suspend fun setPaid(childId: String, week: IsoWeek, paid: Boolean) {
        val current = docs.get(Collections.Allowances, childId, Allowance.serializer()) ?: Allowance(childId)
        val weeks = if (paid) current.paidWeeks + (week.key to clock.now()) else current.paidWeeks - week.key
        save(current.copy(paidWeeks = weeks))
    }
}

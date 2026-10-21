package se.pusslet.feature.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.pusslet.core.model.FamilyTask

/**
 * The task the editor just deleted, so the list it returns to can say "Dammsuga borttagen · Ångra" (UX: Ångra instead of
 * "Är du säker?"). Deleting is soft, so saving the task again brings it back everywhere. One per app, in memory only.
 */
class DeletedTasks {
    private val state = MutableStateFlow<FamilyTask?>(null)
    val last: StateFlow<FamilyTask?> = state.asStateFlow()

    fun put(task: FamilyTask) {
        state.value = task
    }

    /** Only clears [task]: a newer deletion keeps its receipt. */
    fun clear(task: FamilyTask) {
        state.compareAndSet(task, null)
    }
}

package se.pusslet.feature.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.pusslet.core.model.FamilyTask

/**
 * The chores "Vanliga sysslor" just added (ADR-065), so the list it returns to can say "5 sysslor tillagda · Ångra". One per
 * app, in memory only.
 */
class AddedChores {
    private val state = MutableStateFlow<List<FamilyTask>>(emptyList())
    val last: StateFlow<List<FamilyTask>> = state.asStateFlow()

    fun put(tasks: List<FamilyTask>) {
        state.value = tasks
    }

    /** Only clears [tasks]: a newer batch keeps its receipt. */
    fun clear(tasks: List<FamilyTask>) {
        state.compareAndSet(tasks, emptyList())
    }
}

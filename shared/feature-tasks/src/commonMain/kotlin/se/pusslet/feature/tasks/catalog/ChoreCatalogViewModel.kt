package se.pusslet.feature.tasks.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Role
import se.pusslet.core.tasks.CatalogChore
import se.pusslet.core.tasks.ChoreCatalog
import se.pusslet.core.tasks.ChoreOwner
import se.pusslet.core.tasks.PickedChore
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.feature.tasks.AddedChores
import kotlin.time.Clock

data class ChoreCatalogUiState(
    val loading: Boolean = true,
    /** Parents add chores; a caregiver never gets here, and the rules would refuse it. */
    val canEdit: Boolean = false,
    /** Who can take the chores: the parents, not the caregivers or the children. */
    val parents: List<FamilyPerson> = emptyList(),
    val owner: ChoreOwner = ChoreOwner.Nobody,
    val picked: Set<CatalogChore> = emptySet(),
    /** The family's tasks by title in lower case: what they already have is not offered again. */
    val existingTitles: Set<String> = emptySet(),
    val done: Boolean = false,
) {
    fun exists(title: String): Boolean = title.trim().lowercase() in existingTitles

    /** What "Lägg till" adds: what is picked, less what the family already has. [titles] are the names in the app's language. */
    fun toAdd(titles: Map<CatalogChore, String>): List<CatalogChore> =
        CatalogChore.entries.filter { it in picked && !exists(titles[it].orEmpty()) }
}

/**
 * "Vanliga sysslor" (ADR-065): pick chores instead of typing them. They become ordinary chores that can be changed one by one,
 * and the list it returns to offers "Ångra".
 */
class ChoreCatalogViewModel(
    private val tasks: TaskRepository,
    family: FamilyRepository,
    private val sessions: SessionProvider,
    private val added: AddedChores,
    private val clock: Clock,
    private val zone: TimeZone,
    private val ids: IdGenerator,
) : ViewModel() {
    private val picked = MutableStateFlow<Set<CatalogChore>>(emptySet())
    private val chosenOwner = MutableStateFlow<ChoreOwner?>(null)
    private val done = MutableStateFlow(false)
    private var saving = false

    private val choices = combine(picked, chosenOwner, done) { p, o, d -> Triple(p, o, d) }

    val state: StateFlow<ChoreCatalogUiState> = combine(tasks.tasks, family.people, sessions.session, choices) {
        all,
        people,
        s,
        choice,
        ->
        val parents = people.filter { !it.isChild && it.role == Role.Parent }
        ChoreCatalogUiState(
            loading = false,
            canEdit = s.role == Role.Parent,
            parents = parents,
            owner = choice.second ?: defaultOwner(parents),
            picked = choice.first,
            existingTitles = all.map { it.title.trim().lowercase() }.toSet(),
            done = choice.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChoreCatalogUiState())

    fun toggle(chore: CatalogChore) = picked.update { if (chore in it) it - chore else it + chore }

    fun setOwner(owner: ChoreOwner) {
        chosenOwner.value = owner
    }

    /** Saves the picked chores, under their names in [titles], and hands them to the list for its "Ångra". */
    fun add(titles: Map<CatalogChore, String>) {
        val s = state.value
        val chores = s.toAdd(titles)
        if (!s.canEdit || saving || s.done || chores.isEmpty()) return
        saving = true
        val new = ChoreCatalog.tasks(
            chores.map { PickedChore(ids.newId(), titles.getValue(it), it) },
            s.owner,
            clock.todayIn(zone),
            sessions.session.value.memberId,
            clock.now(),
        )
        viewModelScope.launch {
            new.forEach { tasks.save(it) }
            added.put(new)
            done.value = true
        }
    }

    /** Two parents take turns; one parent has them; without a parent (not a real family yet) nobody does. */
    private fun defaultOwner(parents: List<FamilyPerson>): ChoreOwner = when {
        parents.size > 1 -> ChoreOwner.TakeTurns(parents.map { it.id })
        parents.size == 1 -> ChoreOwner.Person(parents.single().id)
        else -> ChoreOwner.Nobody
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

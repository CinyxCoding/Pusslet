package se.pusslet.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.pusslet.core.model.Role
import se.pusslet.core.today.StoredTodayLayout
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayout
import se.pusslet.core.today.TodayLayouts
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider

data class TodayLayoutUiState(
    val loading: Boolean = true,
    /** The viewer's resolved layout: only the blocks their role has, Morgonkollen first. */
    val layout: TodayLayout = TodayLayouts.defaultFor(Role.Parent),
    /** "Återställ till standard" only shows when something differs from the role's default. */
    val isDefault: Boolean = true,
    /** Without a place the weather row says where to choose one. */
    val hasWeatherPlace: Boolean = true,
)

/**
 * "Anpassa Idag" (UX plan Steg 2, ADR-027): switches and order for the blocks of Idag. Every change is written to
 * Prefs.TodayLayout at once (device-local, only what differs from the role's default), so Idag updates while you are here.
 */
class TodayLayoutViewModel(
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    family: FamilyRepository,
) : ViewModel() {
    /** Taps can come faster than the preference store answers; each change reads and writes under the lock. */
    private val writes = Mutex()

    val state: StateFlow<TodayLayoutUiState> = combine(
        sessions.session,
        prefs.observe(Prefs.TodayLayout),
        family.settings,
    ) { session, stored, settings ->
        val layout = TodayLayouts.resolve(session.role, session.memberId, stored)
        TodayLayoutUiState(
            loading = false,
            layout = layout,
            isDefault = TodayLayouts.isDefault(layout, session.role),
            hasWeatherPlace = settings.weatherPlace != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayLayoutUiState())

    fun setVisible(block: TodayBlock, visible: Boolean) = change { TodayLayouts.setVisible(it, block, visible) }

    fun move(block: TodayBlock, up: Boolean) = change { TodayLayouts.move(it, block, up) }

    /** Back to the role's default. Returns what was stored, so the snackbar can put it back ([restore]). */
    suspend fun reset(): StoredTodayLayout? = writes.withLock {
        val previous = prefs.get(Prefs.TodayLayout)
        prefs.set(Prefs.TodayLayout, null)
        previous
    }

    /** "Ångra" after [reset]. */
    fun restore(stored: StoredTodayLayout?) {
        viewModelScope.launch { writes.withLock { prefs.set(Prefs.TodayLayout, stored) } }
    }

    private fun change(transform: (TodayLayout) -> TodayLayout) {
        viewModelScope.launch {
            writes.withLock {
                val session = sessions.session.value
                // Without a member there is nobody to remember the layout for; Idag shows the role's default then.
                val memberId = session.memberId ?: return@withLock
                val current = TodayLayouts.resolve(session.role, memberId, prefs.get(Prefs.TodayLayout))
                prefs.set(Prefs.TodayLayout, TodayLayouts.toStored(transform(current), memberId, session.role))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

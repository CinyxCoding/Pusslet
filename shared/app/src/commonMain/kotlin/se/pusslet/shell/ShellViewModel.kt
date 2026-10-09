package se.pusslet.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.pusslet.data.AppCoordinator
import se.pusslet.data.repository.AppTheme
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.sync.SyncState
import se.pusslet.ui.theme.ThemeMode

data class ShellState(
    val ready: Boolean = false,
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    /** Offline with changes waiting to be sent: say so, so nobody wonders why the other phone has not got them yet. */
    val offlineWithChanges: Boolean = false,
)

/** App-wide state the root composable needs: start-up done, onboarding gate, theme and offline notice. */
class ShellViewModel(
    coordinator: AppCoordinator,
    prefs: PreferencesRepository,
    sessions: SessionProvider,
    sync: SyncEngine,
) : ViewModel() {
    private val offline = sync.status.map { it.state == SyncState.Offline && it.pendingWrites > 0 }.distinctUntilChanged()

    val state: StateFlow<ShellState> = combine(
        coordinator.ready,
        prefs.observe(Prefs.OnboardingDone),
        prefs.observe(Prefs.Theme),
        combine(prefs.observe(Prefs.DynamicColor), offline) { dynamic, off -> dynamic to off },
        sessions.session,
    ) { ready, onboardingDone, theme, (dynamic, off), session ->
        ShellState(
            ready = ready,
            // Forgetting the family on this device brings the user back to onboarding.
            onboarded = onboardingDone && session.hasFamily,
            theme = when (theme) {
                AppTheme.System -> ThemeMode.System
                AppTheme.Light -> ThemeMode.Light
                AppTheme.Dark -> ThemeMode.Dark
            },
            dynamicColor = dynamic,
            offlineWithChanges = off,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellState())
}

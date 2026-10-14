package se.pusslet.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.pusslet.core.model.Role
import se.pusslet.data.AppCoordinator
import se.pusslet.data.repository.AppTheme
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.theme.ThemeMode

/** Which shell the app shows once onboarded. */
enum class ShellMode {
    /** Bottom bar with Idag · Kalender · Mat · Handla · Mer, and the round +. */
    Main,

    /** The simple helper view for caregivers (UX plan Steg 3, ADR-029): `HelperShell`, no bottom bar and no +. */
    Helper,
}

data class ShellState(
    val ready: Boolean = false,
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val mode: ShellMode = ShellMode.Main,
    /** A caregiver in the full app: Mer offers "Enkel vy" to go back to the helper view. */
    val caregiverInFullApp: Boolean = false,
)

/** App-wide state the root composable needs: start-up done, onboarding gate, theme and which shell to show. */
class ShellViewModel internal constructor(
    ready: Flow<Boolean>,
    private val prefs: PreferencesRepository,
    sessions: SessionProvider,
) : ViewModel() {
    constructor(coordinator: AppCoordinator, prefs: PreferencesRepository, sessions: SessionProvider) :
        this(coordinator.ready, prefs, sessions)

    private data class Look(val theme: AppTheme, val dynamic: Boolean)

    val state: StateFlow<ShellState> = combine(
        ready,
        prefs.observe(Prefs.OnboardingDone),
        combine(prefs.observe(Prefs.Theme), prefs.observe(Prefs.DynamicColor), ::Look),
        prefs.observe(Prefs.HelperFullView),
        sessions.session,
    ) { ready, onboardingDone, look, fullView, session ->
        ShellState(
            ready = ready,
            // Forgetting the family on this device brings the user back to onboarding.
            onboarded = onboardingDone && session.hasFamily,
            theme = when (look.theme) {
                AppTheme.System -> ThemeMode.System
                AppTheme.Light -> ThemeMode.Light
                AppTheme.Dark -> ThemeMode.Dark
            },
            dynamicColor = look.dynamic,
            mode = modeFor(session, fullView),
            caregiverInFullApp = session.role == Role.Caregiver && fullView,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellState())

    /** "Visa hela appen" (true) in the helper view, "Enkel vy" (false) in Mer. Stored on this device only. */
    fun setHelperFullView(fullView: Boolean) {
        viewModelScope.launch { prefs.set(Prefs.HelperFullView, fullView) }
    }

    internal companion object {
        /** Caregivers get the helper view unless they chose the full app; parents never do because of their role. */
        fun modeFor(session: Session, fullView: Boolean): ShellMode =
            if (session.role == Role.Caregiver && !fullView) ShellMode.Helper else ShellMode.Main
    }
}

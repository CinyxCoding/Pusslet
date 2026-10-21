package se.pusslet.feature.tasks.distribution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.model.Role
import se.pusslet.core.tasks.Distribution
import se.pusslet.core.tasks.TaskDistribution
import se.pusslet.core.time.isoWeek
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

data class DistributionUiState(
    val loading: Boolean = true,
    /** Parents only: the view is about how the family's adults shared things. */
    val allowed: Boolean = false,
    val distribution: Distribution? = null,
)

/**
 * "Så har vi delat" (BRIEF §4.7): the last four ISO weeks, per adult. Hand-offs are read from every occurrence (not
 * Kalender's filter, ADR-031) with accepted answers in effect, and counted as planned.
 */
class DistributionViewModel(
    tasks: TaskRepository,
    family: FamilyRepository,
    calendar: CalendarRepository,
    sessions: SessionProvider,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val today = clock.todayIn(zone)
    private val from = today.isoWeek.plusWeeks(-(TaskDistribution.WEEKS - 1)).monday

    val state: StateFlow<DistributionUiState> = combine(
        tasks.tasks,
        family.people,
        calendar.allOccurrences(from, today, zone),
        calendar.handoffRequests,
        sessions.session,
    ) { all, people, occurrences, requests, session ->
        if (session.role != Role.Parent) return@combine DistributionUiState(loading = false)
        val effective = EffectiveHandoffs.apply(occurrences, requests)
        DistributionUiState(
            loading = false,
            allowed = true,
            distribution = TaskDistribution.compute(today, zone, people, all, effective, requests),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DistributionUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

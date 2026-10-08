package se.pusslet.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Role
import se.pusslet.core.tasks.PersonLoad
import se.pusslet.core.tasks.Workload
import se.pusslet.core.tasks.WorkloadReport
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class WorkloadUiState(
    val loading: Boolean = true,
    val report: WorkloadReport? = null,
    val people: Map<String, FamilyPerson> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkloadViewModel(
    tasks: TaskRepository,
    family: FamilyRepository,
    calendar: CalendarRepository,
    clock: Clock,
    zone: TimeZone,
) : ViewModel() {
    private val today = clock.todayIn(zone)

    val state: StateFlow<WorkloadUiState> = combine(
        tasks.tasks,
        family.people,
        calendar.occurrences(today.minusDays(WEEKS * DAYS_PER_WEEK), today.plusDays(1), zone),
    ) { t, people, occ ->
        // Between the adults who run the household; helpers like morfar are not compared.
        val adults = people.filter { !it.isChild && it.role != Role.Caregiver }
        WorkloadUiState(false, Workload.report(adults.map { it.id }, t, occ, today, zone, WEEKS), people.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WorkloadUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WEEKS = 4
        const val DAYS_PER_WEEK = 7
    }
}

@Composable
fun WorkloadRoute(onBack: () -> Unit, viewModel: WorkloadViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkloadScreen(state, onBack)
}

/** Plain counts side by side on one scale: something to talk about, not a score. */
@Composable
fun WorkloadScreen(state: WorkloadUiState, onBack: () -> Unit = {}) {
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.workload_title), onBack = onBack) }) { padding ->
        val report = state.report
        if (state.loading || report == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.workload_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(Res.string.workload_period, formatDateShort(report.from), formatDateShort(report.to)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.isEmpty) {
                EmptyState(stringResource(Res.string.workload_empty))
                return@Column
            }
            val scale = report.maxCount.coerceAtLeast(1)
            listOf(
                Res.string.workload_tasks to { p: PersonLoad -> p.tasks },
                Res.string.workload_chores to { p: PersonLoad -> p.chores },
                Res.string.workload_dropoffs to { p: PersonLoad -> p.dropOffs },
                Res.string.workload_pickups to { p: PersonLoad -> p.pickUps },
            ).forEach { (label, value) -> Category(label, report.people, state.people, scale, value) }
        }
    }
}

@Composable
private fun Category(
    label: StringResource,
    loads: List<PersonLoad>,
    people: Map<String, FamilyPerson>,
    scale: Int,
    value: (PersonLoad) -> Int,
) {
    SectionCard(stringResource(label)) {
        loads.forEach { load ->
            val person = people[load.personId] ?: return@forEach
            val count = value(load)
            val description = stringResource(Res.string.workload_bar, person.name, count)
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PersonAvatar(person, size = 28.dp)
                Text(
                    person.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.width(NAME_WIDTH),
                )
                Box(Modifier.weight(1f).height(BAR_HEIGHT)) {
                    val colors = PussletThemeExtras.colors.member(person.color)
                    if (count > 0) {
                        Box(
                            Modifier.fillMaxWidth(count.toFloat() / scale).height(BAR_HEIGHT)
                                .background(colors.container, RoundedCornerShape(BAR_HEIGHT / 2)),
                        )
                    }
                }
                Text(count.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(COUNT_WIDTH))
            }
        }
    }
}

private val BAR_HEIGHT = 20.dp
private val COUNT_WIDTH = 32.dp
private val NAME_WIDTH = 72.dp

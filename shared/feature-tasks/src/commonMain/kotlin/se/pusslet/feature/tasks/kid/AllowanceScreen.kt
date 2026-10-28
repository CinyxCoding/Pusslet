package se.pusslet.feature.tasks.kid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Allowance
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.tasks.KidChores
import se.pusslet.core.tasks.PendingApproval
import se.pusslet.core.tasks.StarWeek
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.usecase.AllowanceRepository
import se.pusslet.data.usecase.KidChoreActions
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.allowance_body
import se.pusslet.ui.resources.allowance_paid
import se.pusslet.ui.resources.allowance_per_star
import se.pusslet.ui.resources.allowance_title
import se.pusslet.ui.resources.allowance_week_line
import se.pusslet.ui.resources.allowance_weekly
import se.pusslet.ui.resources.kid_mode
import se.pusslet.ui.resources.kids_approve
import se.pusslet.ui.resources.kids_done_line
import se.pusslet.ui.resources.kids_nothing_to_approve
import se.pusslet.ui.resources.kids_reject
import se.pusslet.ui.resources.kids_title
import se.pusslet.ui.resources.kids_to_approve
import se.pusslet.ui.resources.stars_count
import se.pusslet.ui.resources.week_long
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

data class ChildAllowance(val child: FamilyPerson, val settings: Allowance, val weeks: List<StarWeek>)

data class AllowanceUiState(
    val loading: Boolean = true,
    val children: List<ChildAllowance> = emptyList(),
    /** The children's "Klar!" waiting for a parent. */
    val approvals: List<PendingApproval> = emptyList(),
)

/**
 * "Barnen" (ADR-055): the children's ticks to approve, then veckopeng – a base per week plus kronor per approved star, and
 * which weeks are paid. Parents only.
 */
class AllowanceViewModel(
    tasks: TaskRepository,
    family: FamilyRepository,
    private val allowances: AllowanceRepository,
    private val kid: KidChoreActions,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    val state: StateFlow<AllowanceUiState> = combine(tasks.tasks, family.people, allowances.allowances) { all, people, money ->
        val thisWeek = clock.todayIn(zone).isoWeek
        val weeks = (0 until WEEKS).map { thisWeek.monday.plusDays(-WEEK_DAYS * it).isoWeek }
        val children = people.filter { it.isChild }
        AllowanceUiState(
            loading = false,
            approvals = KidChores.pending(all, children.map { it.id }.toSet(), zone),
            children = people.filter { it.isChild }.map { child ->
                val a = money.firstOrNull { it.childId == child.id } ?: Allowance(child.id)
                ChildAllowance(child, a, weeks.map { w -> KidChores.week(child.id, all, w, a, zone) })
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AllowanceUiState())

    fun setAmounts(childId: String, weekly: Int?, perStar: Int?) {
        val current = state.value.children.firstOrNull { it.child.id == childId }?.settings ?: Allowance(childId)
        viewModelScope.launch {
            allowances.save(current.copy(weekly = weekly ?: current.weekly, perStar = perStar ?: current.perStar))
        }
    }

    fun setPaid(childId: String, week: IsoWeek, paid: Boolean) {
        viewModelScope.launch { allowances.setPaid(childId, week, paid) }
    }

    fun approve(p: PendingApproval) {
        viewModelScope.launch { kid.approve(p) }
    }

    /** "Inte klart": the chore goes back on the child's list. */
    fun reject(p: PendingApproval) {
        viewModelScope.launch { kid.reject(p) }
    }

    private companion object {
        const val WEEKS = 4
        const val WEEK_DAYS = 7
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun AllowanceRoute(onBack: () -> Unit, onKidMode: () -> Unit = {}, viewModel: AllowanceViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.kids_title), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "approvals") { Approvals(state.approvals, state.children, viewModel::approve, viewModel::reject) }
            item(key = "kid-mode") { OutlinedButton(onClick = onKidMode) { Text(stringResource(Res.string.kid_mode)) } }
            item(key = "title") { Text(stringResource(Res.string.allowance_title), style = MaterialTheme.typography.titleMedium) }
            item(key = "body") {
                Text(
                    stringResource(Res.string.allowance_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.children, key = { it.child.id }) { c -> ChildCard(c, viewModel) }
        }
    }
}

@Composable
private fun ChildCard(c: ChildAllowance, viewModel: AllowanceViewModel) {
    SectionCard(c.child.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(c.child, size = AVATAR.dp)
            MoneyField(stringResource(Res.string.allowance_weekly), c.settings.weekly, Modifier.weight(1f)) {
                viewModel.setAmounts(c.child.id, it, null)
            }
            MoneyField(stringResource(Res.string.allowance_per_star), c.settings.perStar, Modifier.weight(1f)) {
                viewModel.setAmounts(c.child.id, null, it)
            }
        }
        c.weeks.forEach { w ->
            val paid = w.week.key in c.settings.paidWeeks
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.week_long, w.week.week),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(WEEK_WEIGHT),
                )
                Text(
                    stringResource(Res.string.allowance_week_line, w.approved, w.amount ?: 0),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Checkbox(checked = paid, onCheckedChange = { viewModel.setPaid(c.child.id, w.week, it) })
                Text(stringResource(Res.string.allowance_paid), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MoneyField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(MAX_DIGITS).toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val AVATAR = 40
private const val WEEK_WEIGHT = 0.6f
private const val MAX_DIGITS = 4

/** The children's "Klar!" waiting for a parent: "Ella: Duka av · 2 ⭐" with Godkänn and Inte klart. */
@Composable
private fun Approvals(
    approvals: List<PendingApproval>,
    children: List<ChildAllowance>,
    onApprove: (PendingApproval) -> Unit,
    onReject: (PendingApproval) -> Unit,
) {
    SectionCard(stringResource(Res.string.kids_to_approve)) {
        if (approvals.isEmpty()) Text(stringResource(Res.string.kids_nothing_to_approve), style = MaterialTheme.typography.bodyMedium)
        approvals.forEach { p ->
            val who = children.firstOrNull { it.child.id == p.childId }?.child?.name.orEmpty()
            Column {
                Text(stringResource(Res.string.kids_done_line, who, p.task.title), style = MaterialTheme.typography.bodyLarge)
                if (p.stars > 0) Text(stringResource(Res.string.stars_count, p.stars), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { onApprove(p) }) { Text(stringResource(Res.string.kids_approve)) }
                    TextButton(onClick = { onReject(p) }) { Text(stringResource(Res.string.kids_reject)) }
                }
            }
        }
    }
}

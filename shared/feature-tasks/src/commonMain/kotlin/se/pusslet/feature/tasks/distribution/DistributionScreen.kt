package se.pusslet.feature.tasks.distribution

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.tasks.Distribution
import se.pusslet.core.tasks.ShareKind
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.Section
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.distribution_chores
import se.pusslet.ui.resources.distribution_empty
import se.pusslet.ui.resources.distribution_explain
import se.pusslet.ui.resources.distribution_former
import se.pusslet.ui.resources.distribution_handoffs
import se.pusslet.ui.resources.distribution_purpose
import se.pusslet.ui.resources.distribution_requests
import se.pusslet.ui.resources.distribution_row_a11y
import se.pusslet.ui.resources.distribution_tasks
import se.pusslet.ui.resources.distribution_weeks
import se.pusslet.ui.resources.tasks_distribution
import se.pusslet.ui.theme.PussletThemeExtras
import se.pusslet.ui.theme.Spacing

@Composable
fun TaskDistributionRoute(onBack: () -> Unit, viewModel: DistributionViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskDistributionScreen(state, onBack)
}

/**
 * "Så har vi delat" (BRIEF §4.7): calm and plain, for a talk and not for blame. Each kind on its own with a bar per adult
 * in the adult's own colour (never a judging colour), the number next to the name, and one line on what is counted.
 */
@Composable
fun TaskDistributionScreen(state: DistributionUiState, onBack: () -> Unit = {}) {
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.tasks_distribution), onBack = onBack) }) { padding ->
        val d = state.distribution
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            !state.allowed || d == null -> Box(Modifier.padding(padding)) { EmptyState(stringResource(Res.string.distribution_empty)) }
            else -> DistributionBody(d, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DistributionBody(d: Distribution, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(Res.string.distribution_weeks, d.fromWeek.week, d.toWeek.week), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(Res.string.distribution_purpose),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(Res.string.distribution_explain), style = MaterialTheme.typography.bodyMedium)
        }
        if (d.isEmpty) {
            EmptyState(stringResource(Res.string.distribution_empty))
            return@Column
        }
        ShareKind.entries.forEach { kind ->
            val rows = d.rows(kind)
            if (rows.isEmpty()) return@forEach
            val max = rows.maxOf { it.count(kind) }.coerceAtLeast(1)
            Section(stringResource(label(kind))) {
                rows.forEach { ShareRow(it.person, it.count(kind), max) }
            }
        }
        val former = d.formerCount()
        if (former > 0) {
            Text(
                stringResource(Res.string.distribution_former, former),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "(S) Sara  6" with a bar below in Sara's colour. One TalkBack node: "Sara: 6". */
@Composable
private fun ShareRow(person: FamilyPerson, count: Int, max: Int) {
    val description = stringResource(Res.string.distribution_row_a11y, person.name, count)
    val colors = PussletThemeExtras.colors.member(person.color)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PersonAvatar(person, size = 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(count.toString(), style = MaterialTheme.typography.bodyLarge)
            }
            if (count > 0) {
                val shape = RoundedCornerShape(BarHeight / 2)
                Box(
                    Modifier
                        .fillMaxWidth((count.toFloat() / max).coerceIn(MIN_BAR, 1f))
                        .height(BarHeight)
                        .background(colors.container, shape)
                        .border(1.dp, colors.content, shape),
                )
            }
        }
    }
}

private fun label(kind: ShareKind): StringResource = when (kind) {
    ShareKind.Tasks -> Res.string.distribution_tasks
    ShareKind.Chores -> Res.string.distribution_chores
    ShareKind.Handoffs -> Res.string.distribution_handoffs
    ShareKind.Requests -> Res.string.distribution_requests
}

private val BarHeight = 10.dp
private const val MIN_BAR = 0.04f

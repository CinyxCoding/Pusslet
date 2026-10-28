package se.pusslet.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.data.repository.CalendarLayer
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** "Filtrera" (UX plan, Kalender 3): the children first, then the adults, then the layers. */
@Composable
internal fun CalendarFilterSheet(
    state: CalendarUiState,
    onTogglePerson: (String) -> Unit,
    onClearPeople: () -> Unit,
    onToggleLayer: (CalendarLayer) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(Res.string.calendar_layers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val filterable = state.people.filter { !it.otherHome }
            if (filterable.isNotEmpty()) {
                SheetHeading(stringResource(Res.string.calendar_filter_people))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = state.personFilter.isEmpty(),
                        onClick = onClearPeople,
                        label = { Text(stringResource(Res.string.calendar_filter_all)) },
                        colors = SelectionColors.filterChip(),
                    )
                    filterable.sortedBy { !it.isChild }.forEach { p ->
                        FilterChip(
                            selected = p.id in state.personFilter,
                            onClick = { onTogglePerson(p.id) },
                            label = { Text(p.name) },
                            leadingIcon = { PersonAvatar(p, size = 20.dp) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                }
            }
            SheetHeading(stringResource(Res.string.calendar_filter_layers))
            CalendarLayer.entries.forEach { layer ->
                SwitchRow(stringResource(layerLabel(layer)), layer in state.layers, { onToggleLayer(layer) })
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
    )
}

/** "Bara Simon · Dolt: Helgdagar": what the filter hides, or null when nothing is hidden. */
@Composable
internal fun filterSummary(state: CalendarUiState): String? {
    val parts = buildList {
        val only = state.filteredPeople
        if (only.isNotEmpty()) add(stringResource(Res.string.calendar_filter_only, names(only)))
        val hidden = state.hiddenLayers.map { stringResource(layerLabel(it)) }
        if (hidden.isNotEmpty()) add(stringResource(Res.string.calendar_filter_hidden, joinNames(hidden)))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
}

internal fun layerLabel(layer: CalendarLayer): StringResource = when (layer) {
    CalendarLayer.Family -> Res.string.layer_family
    CalendarLayer.Subscriptions -> Res.string.layer_subscriptions
    CalendarLayer.Device -> Res.string.layer_device
    CalendarLayer.Holidays -> Res.string.layer_holidays
    CalendarLayer.Custody -> Res.string.layer_custody
}

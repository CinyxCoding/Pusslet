package se.pusslet.feature.today.helper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.helper.HelperWeekDay
import se.pusslet.core.helper.HelperWeekItem
import se.pusslet.core.model.EventOccurrence
import se.pusslet.feature.today.SEPARATOR
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.QuietLine
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.helper_week
import se.pusslet.ui.resources.helper_week_empty
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.Spacing

/**
 * "Hela veckan" (UX plan Steg 3): the next 14 days in one simple list with everything shared with the caregiver. His own
 * missions are in bold ("Du hämtar Simon 15:30"), red days are text, and a tap opens the event (read-only for him).
 */
@Composable
fun HelperWeekRoute(onBack: () -> Unit, onOpenOccurrence: (EventOccurrence) -> Unit, viewModel: HelperViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.helper_week), onBack = onBack) }) { padding ->
        val plan = state.plan
        if (state.loading || plan == null) {
            LoadingBox()
        } else {
            HelperWeekScreen(plan.days, padding, onOpenOccurrence)
        }
    }
}

@Composable
fun HelperWeekScreen(
    days: List<HelperWeekDay>,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenOccurrence: (EventOccurrence) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.sm + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl + contentPadding.calculateBottomPadding(),
        ),
    ) {
        if (days.isEmpty()) item(key = "empty") { QuietLine(stringResource(Res.string.helper_week_empty)) }
        days.forEach { day ->
            item(key = "day-${day.date}") { DayHeader(day) }
            items(day.items, key = { "item-${day.date}-${itemKey(it)}" }) { item ->
                when (item) {
                    is HelperWeekItem.Mission -> WeekRow(
                        time = formatTime(item.mission.time),
                        text = missionText(item.mission).text,
                        supporting = listOfNotNull(item.mission.title, item.mission.place).joinToString(SEPARATOR).ifEmpty { null },
                        mine = true,
                        onClick = { onOpenOccurrence(item.mission.occurrence) },
                    )
                    is HelperWeekItem.Event -> WeekRow(
                        time = item.time?.let(::formatTime),
                        text = item.occurrence.event.title,
                        supporting = listOfNotNull(
                            names(item.people).ifEmpty { null },
                            item.occurrence.event.location.trim().ifEmpty { null },
                        ).joinToString(SEPARATOR).ifEmpty { null },
                        mine = false,
                        onClick = { onOpenOccurrence(item.occurrence) },
                    )
                }
            }
        }
    }
}

private fun itemKey(item: HelperWeekItem): String = when (item) {
    is HelperWeekItem.Mission -> item.mission.key
    is HelperWeekItem.Event -> item.occurrence.key
}

/** "Fredag 9 oktober · vecka 42" and the red days under it. */
@Composable
private fun DayHeader(day: HelperWeekDay) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl, bottom = Spacing.xs)) {
        Text(dayTitle(day.date, day.week), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        day.holidays.forEach {
            Text(stringResource(Labels.holiday(it.id)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The time in its own column, then the sentence; his own missions in bold. The whole row opens the event. */
@Composable
private fun WeekRow(time: String?, text: String, supporting: String?, mine: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HelperDimens.row)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(time.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = TIME_COLUMN))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (mine) FontWeight.SemiBold else null)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        Icon(PussletIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** Room for "15:45" at 18 sp. */
private val TIME_COLUMN = 64.dp

package se.pusslet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.today
import se.pusslet.ui.theme.Spacing

/**
 * Picks one day of a week: seven chips Monday to Sunday ("mån" over "13"), the chosen day in sage (`primaryContainer`) and
 * today outlined. Use it in sheets where the day can be changed, such as the meal planning sheet; to step whole weeks use
 * [WeekSwitcher].
 *
 * Pass the days in order, normally the seven days of one ISO week. TalkBack reads each chip as a radio button with the full
 * date ("torsdag 8 oktober, Idag"). The chips share the width equally (about 50 dp each on a 411 dp phone) and are 56 dp
 * tall; with very large text they wrap onto a second line instead of being cut.
 */
@Composable
fun WeekStripPicker(
    days: List<LocalDate>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
) {
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        days.forEach { day ->
            DayChip(
                day = day,
                selected = day == selected,
                isToday = day == today,
                onClick = { onSelect(day) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayChip(day: LocalDate, selected: Boolean, isToday: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = formatDateLong(day) + if (isToday) ", " + stringResource(Res.string.today) else ""
    val shape = MaterialTheme.shapes.small
    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface
    val weight = if (isToday || selected) FontWeight.SemiBold else null
    Column(
        modifier
            .heightIn(min = Spacing.primaryTouchTarget)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.primaryContainer) else Modifier)
            .then(if (isToday && !selected) Modifier.border(1.dp, colors.primary, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The chip's description is the full date; the short visible texts would only repeat it.
        Text(
            weekdayShort(day.dayOfWeek),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = weight,
            color = contentColor,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            day.day.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = weight,
            color = contentColor,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

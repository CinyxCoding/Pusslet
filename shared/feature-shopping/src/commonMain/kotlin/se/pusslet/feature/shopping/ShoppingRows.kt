package se.pusslet.feature.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.units.Units
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import se.pusslet.ui.theme.StoreModeItemStyle

/**
 * The one detail line of a row, at most three parts: "2 l · till Tacos fre · laktosfri". The meal part comes from the meal
 * plan ("Handla till veckan"); null when there is nothing to say.
 */
@Composable
internal fun itemDetails(item: ShoppingItem): String? {
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    val meals = item.sources
        .filter { it.label.isNotBlank() }
        .map { source ->
            val day = source.mealDate?.let { weekdays[it.dayOfWeek.isoDayNumber - 1] }
            if (day == null) source.label.trim() else stringResource(Res.string.shopping_meal_day, source.label.trim(), day)
        }
        .distinct()
    val parts = listOfNotNull(
        item.amount?.let { Units.format(it) },
        meals.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.shopping_for_meal, it.joinToString(", ")) },
        item.note.trim().takeIf { it.isNotEmpty() },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * One line on the list, like on paper: checkbox, name and one detail line, nothing else. The checkbox ticks off (with a
 * subtle haptic tick) and the text opens editing. For TalkBack the row is one checkbox node; editing and [onRemove] are
 * custom actions. [large] is store mode ("stor text"): there the whole row ticks off and a long press edits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemRow(
    item: ShoppingItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val details = itemDetails(item)
    val checkedLabel = stringResource(if (item.checked) Res.string.shopping_state_in_cart else Res.string.shopping_state_to_buy)
    val toggleLabel = stringResource(if (item.checked) Res.string.shopping_put_back else Res.string.shopping_tick_off)
    val editLabel = stringResource(Res.string.action_edit)
    val removeLabel = stringResource(Res.string.action_delete)
    val toggle = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onToggle(!item.checked)
    }
    if (large) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = LARGE_ROW)
                .combinedClickable(
                    role = Role.Checkbox,
                    onClickLabel = toggleLabel,
                    onLongClickLabel = editLabel,
                    onLongClick = onEdit,
                    onClick = toggle,
                )
                .semantics { stateDescription = checkedLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Checkbox(checked = item.checked, onCheckedChange = null)
            ItemText(item, details, large = true, modifier = Modifier.weight(1f).padding(vertical = Spacing.xs))
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.primaryTouchTarget)
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(item.name, details).joinToString(", ")
                    role = Role.Checkbox
                    toggleableState = ToggleableState(item.checked)
                    stateDescription = checkedLabel
                    onClick(label = toggleLabel) {
                        toggle()
                        true
                    }
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(editLabel) {
                            onEdit()
                            true
                        },
                        onRemove?.let { remove ->
                            CustomAccessibilityAction(removeLabel) {
                                remove()
                                true
                            }
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.checked, onCheckedChange = { toggle() })
            ItemText(
                item,
                details,
                large = false,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.primaryTouchTarget)
                    .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onEdit)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            )
        }
    }
}

@Composable
private fun ItemText(item: ShoppingItem, details: String?, large: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        val style = if (large) StoreModeItemStyle else MaterialTheme.typography.bodyLarge
        Text(
            item.name,
            style = style.dimIf(item.checked),
            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
        )
        if (details != null) {
            Text(
                details,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (large) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextStyle.dimIf(dim: Boolean): TextStyle = if (dim) copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else this

private val LARGE_ROW: Dp = 72.dp

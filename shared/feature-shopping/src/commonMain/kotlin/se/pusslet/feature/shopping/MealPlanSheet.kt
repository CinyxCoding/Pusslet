package se.pusslet.feature.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.shopping.ShoppingText
import se.pusslet.core.time.isoWeek
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekLong
import se.pusslet.ui.format.weekdayShort
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * "Handla till veckan": choose the days (today to Sunday from the start; any of the next 14 days), see what changes and
 * untick what is not needed ("Finns hemma?"), grouped under "Läggs till", "Ändras" and "Tas bort", and confirm.
 */
@Composable
internal fun MealPlanSheet(
    preview: MealPlanPreview?,
    usePantry: Boolean,
    onPreset: (Int) -> Unit,
    onToggleDay: (LocalDate) -> Unit,
    onIngredient: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.meals_to_list_title), style = MaterialTheme.typography.titleLarge)
            if (preview != null) {
                Presets(preview, onPreset)
                DayToggles(preview.window, preview.days, preview.today, onToggleDay)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                when {
                    preview == null -> LoadingBox(Modifier.heightIn(max = Spacing.xxl * 3))
                    preview.days.isEmpty() -> Text(stringResource(Res.string.meals_to_list_no_days))
                    preview.isEmpty -> Text(stringResource(Res.string.meals_to_list_nothing))
                    else -> PlanGroups(preview, onIngredient)
                }
                if (usePantry && !preview?.coveredAtHome.isNullOrEmpty()) {
                    Text(
                        stringResource(Res.string.meals_to_list_covered, joinNames(preview?.coveredAtHome.orEmpty())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(if (usePantry) Res.string.meals_to_list_pantry_on else Res.string.meals_to_list_pantry_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_cancel)) }
                Button(
                    onClick = onConfirm,
                    enabled = preview != null && !preview.plan.isEmpty,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.meals_to_list_confirm)) }
            }
        }
    }
}

/** "Resten av veckan" / "Nästa vecka"; neither is marked when the family picked other days. */
@Composable
private fun Presets(preview: MealPlanPreview, onPreset: (Int) -> Unit) {
    val today = preview.today ?: return
    val rest = today.isoWeek.days.filter { it >= today }.toSet()
    val next = today.isoWeek.next().days.toSet()
    val options = listOf(rest to Res.string.meals_to_list_rest_of_week, next to Res.string.meals_to_list_next_week)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (days, label) ->
            SegmentedButton(
                selected = preview.days == days,
                onClick = { onPreset(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SelectionColors.segmentedButton(),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

/**
 * The next 14 days as day chips under their week ("Vecka 43"), Monday to Sunday in columns; days outside the window
 * leave their column empty. Each chip is a checkbox for TalkBack with the full date.
 */
@Composable
private fun DayToggles(window: List<LocalDate>, chosen: Set<LocalDate>, today: LocalDate?, onToggle: (LocalDate) -> Unit) {
    if (window.isEmpty()) return
    val inWindow = window.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        window.map { it.isoWeek }.distinct().forEach { week ->
            Text(
                weekLong(week.week),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                week.days.forEach { day ->
                    if (day in inWindow) {
                        DayToggle(day, selected = day in chosen, isToday = day == today, onToggle = { onToggle(day) }, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayToggle(day: LocalDate, selected: Boolean, isToday: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = formatDateLong(day) + if (isToday) ", " + stringResource(Res.string.today) else ""
    val shape = MaterialTheme.shapes.small
    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface
    val weight = if (isToday || selected) FontWeight.SemiBold else null
    Column(
        modifier
            .heightIn(min = Spacing.primaryTouchTarget)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.primaryContainer) else Modifier.border(1.dp, colors.outlineVariant, shape))
            .then(if (isToday && !selected) Modifier.border(1.dp, colors.primary, shape) else Modifier)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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

@Composable
private fun PlanGroups(preview: MealPlanPreview, onIngredient: (String, Boolean) -> Unit) {
    val (changes, adds) = preview.choices.partition { it.change }
    ChoiceGroup(stringResource(Res.string.shopping_plan_add), adds, onIngredient)
    ChoiceGroup(stringResource(Res.string.shopping_plan_change), changes, onIngredient, preview.trimmed.map(ShoppingText::line))
    ChoiceGroup(stringResource(Res.string.shopping_plan_remove), emptyList(), onIngredient, preview.removed.map(ShoppingText::line))
}

/** "Läggs till" with a tick per ingredient, then plain lines that follow from the plan; an empty group is not shown. */
@Composable
private fun ChoiceGroup(
    title: String,
    choices: List<IngredientChoice>,
    onIngredient: (String, Boolean) -> Unit,
    lines: List<String> = emptyList(),
) {
    if (choices.isEmpty() && lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        choices.forEach { choice -> ChoiceRow(choice, onIngredient) }
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** "☑ 500 g Nötfärs", with "Finns hemma?" in terracotta when the pantry has some but nobody knows how much. */
@Composable
private fun ChoiceRow(choice: IngredientChoice, onIngredient: (String, Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .toggleable(value = choice.included, role = Role.Checkbox) { onIngredient(choice.key, it) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Checkbox(checked = choice.included, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(
                ShoppingText.line(choice.item),
                style = MaterialTheme.typography.bodyLarge,
                color = if (choice.included) colors.onSurface else colors.onSurfaceVariant,
            )
            if (choice.askIfAtHome) {
                Text(
                    stringResource(Res.string.meals_to_list_ask_home),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.tertiary,
                )
            }
        }
    }
}

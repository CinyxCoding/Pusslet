package se.pusslet.feature.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.meals.EaterWeek
import se.pusslet.core.meals.FoodGroup
import se.pusslet.core.meals.WeekBalance
import se.pusslet.core.meals.WeekNeeds
import se.pusslet.core.model.Child
import se.pusslet.ui.components.SectionFrame
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * "Veckans balans" under the week's days: fish, vegetarian, chicken and meat this week per child (school lunch and dinner)
 * or for the family's dinners, what the rest of the week could use, and "Föreslå veckans middagar" (ADR-037).
 */
@Composable
internal fun BalanceCard(balance: WeekBalance, state: MealsUiState, onSuggest: () -> Unit) {
    SectionFrame {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(Res.string.balance_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            BalanceBody(balance, state.children)
            if (state.offerSuggestWeek) {
                TextButton(onClick = onSuggest) { Text(stringResource(Res.string.meals_suggest_week)) }
            }
        }
    }
}

/** The balance in "Näring för veckan", with what it is based on and Livsmedelsverket's advice. */
@Composable
internal fun ColumnScope.BalanceSection(balance: WeekBalance, children: List<Child>) {
    Text(
        stringResource(Res.string.balance_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
    )
    BalanceBody(balance, children)
    val withLunch = balance.eaters.any { it.childIds.isNotEmpty() }
    Muted(stringResource(if (withLunch) Res.string.balance_basis else Res.string.balance_basis_dinners))
    Muted(stringResource(Res.string.balance_source))
}

@Composable
private fun BalanceBody(balance: WeekBalance, children: List<Child>) {
    if (balance.isEmpty) {
        Muted(stringResource(Res.string.balance_empty))
        return
    }
    val names = children.associate { it.id to it.firstName }
    balance.eaters.forEach { eater ->
        val counts = countsText(eater)
        if (counts.isNotEmpty()) {
            val text = if (eater.childIds.isEmpty()) {
                stringResource(Res.string.balance_dinners, counts)
            } else {
                stringResource(Res.string.balance_eater, joinNames(eater.childIds.mapNotNull(names::get)), counts)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
    notes(balance.needs).forEach { Muted(it) }
}

/** "Fisk 1 · Vegetariskt 2 · Kött 3"; groups with no meal are left out. */
@Composable
private fun countsText(eater: EaterWeek): String {
    val total = eater.total
    val parts = listOfNotNull(
        total[FoodGroup.Fish].takeIf { it > 0 }?.let { stringResource(Res.string.balance_fish, it) },
        total[FoodGroup.Vegetarian].takeIf { it > 0 }?.let { stringResource(Res.string.balance_vegetarian, it) },
        total[FoodGroup.Poultry].takeIf { it > 0 }?.let { stringResource(Res.string.balance_poultry, it) },
        total[FoodGroup.RedMeat].takeIf { it > 0 }?.let { stringResource(Res.string.balance_red_meat, it) },
        total.unknown.takeIf { it > 0 }?.let { stringResource(Res.string.balance_unknown, it) },
    )
    return parts.joinToString(SEPARATOR)
}

@Composable
private fun notes(needs: WeekNeeds): List<String> {
    if (needs.isEmpty) return listOf(stringResource(Res.string.balance_ok))
    return listOfNotNull(
        stringResource(Res.string.balance_need_fish).takeIf { FoodGroup.Fish in needs.wanted },
        stringResource(Res.string.balance_need_vegetarian).takeIf { FoodGroup.Vegetarian in needs.wanted },
        stringResource(Res.string.balance_less_meat).takeIf { needs.lessRedMeat },
    )
}

/**
 * "Förslag för veckan": a dinner for each day still to plan, with why ("Fisk i veckan"). Each can be left out or swapped
 * for the next option; "Lägg in" plans the chosen ones in one change that can be undone.
 */
@Composable
internal fun WeekProposalSheet(
    state: WeekProposalState,
    onSwap: (LocalDate) -> Unit,
    onToggle: (LocalDate) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    MealSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.suggest_week_title))
        when (state.empty) {
            ProposalEmpty.NoRecipes -> Text(stringResource(Res.string.suggest_week_no_recipes), style = MaterialTheme.typography.bodyMedium)
            ProposalEmpty.AllPlanned -> Text(
                stringResource(Res.string.suggest_week_all_planned),
                style = MaterialTheme.typography.bodyMedium,
            )
            null -> {
                Muted(stringResource(Res.string.suggest_week_body))
                state.days.forEach { day -> ProposalRow(day, onSwap, onToggle) }
                Button(onClick = onApply, enabled = state.chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.suggest_week_apply, state.chosen.size))
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(
    day: ProposedDay,
    onSwap: (LocalDate) -> Unit,
    onToggle: (LocalDate) -> Unit,
) {
    val weekday = weekdayName(day.date.dayOfWeek)
    val reasons = reasonTexts(day.suggestion)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            Modifier
                .weight(1f)
                .toggleable(value = day.included, role = Role.Checkbox, onValueChange = { onToggle(day.date) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Checkbox(checked = day.included, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(weekday, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(day.suggestion.recipe.title, style = MaterialTheme.typography.bodyLarge)
                if (reasons.isNotEmpty()) {
                    Text(
                        reasons.joinToString(SEPARATOR),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (day.options.size > 1) {
            val description = stringResource(Res.string.suggest_week_swap_description, weekday)
            TextButton(onClick = { onSwap(day.date) }, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(Res.string.suggest_week_swap))
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val SEPARATOR = " · "

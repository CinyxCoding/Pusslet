package se.pusslet.feature.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** The planning sheet's choice: "Laga mat | Hämtmat | Ingen matlagning". */
internal fun kindChoiceLabel(kind: MealKind): StringResource = when (kind) {
    MealKind.Cooked -> Res.string.meals_kind_cooked
    MealKind.Takeaway -> Res.string.meals_kind_takeaway
    MealKind.NoCooking -> Res.string.meals_kind_none
}

/** What a planned meal says about its kind ("Hämtmat"); cooked meals say nothing. Also the title when none is typed. */
internal fun kindLabel(kind: MealKind): StringResource? = if (kind.cooks) null else kindChoiceLabel(kind)

internal fun kindIcon(kind: MealKind): ImageVector? = when (kind) {
    MealKind.Cooked -> null
    MealKind.Takeaway -> MealsIcons.Takeout
    MealKind.NoCooking -> MealsIcons.NoCooking
}

/** "Laga mat | Hämtmat | Ingen matlagning", like Kalender's "Vecka | Månad". Long labels wrap rather than cut. */
@Composable
internal fun MealKindChoice(kind: MealKind, onKind: (MealKind) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        MealKind.entries.forEachIndexed { i, k ->
            SegmentedButton(
                selected = kind == k,
                onClick = { onKind(k) },
                shape = SegmentedButtonDefaults.itemShape(i, MealKind.entries.size),
                colors = SelectionColors.segmentedButton(),
                label = { Text(stringResource(kindChoiceLabel(k)), maxLines = 2, textAlign = TextAlign.Center) },
            )
        }
    }
}

/** "Vem lagar": one chip per adult and "Ingen vald". */
@Composable
internal fun CookChooser(adults: List<FamilyPerson>, cookId: String?, onCook: (String?) -> Unit) {
    Text(
        stringResource(Res.string.meals_who_cooks),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        adults.forEach { person ->
            FilterChip(
                selected = cookId == person.id,
                onClick = { onCook(person.id) },
                label = { Text(person.name) },
                colors = SelectionColors.filterChip(),
            )
        }
        FilterChip(
            selected = cookId == null || adults.none { it.id == cookId },
            onClick = { onCook(null) },
            label = { Text(stringResource(Res.string.meals_cook_nobody)) },
            colors = SelectionColors.filterChip(),
        )
    }
}

/** "Johan lagar", or "Du lagar" for the viewer; null when nobody is chosen or nothing is cooked. */
@Composable
internal fun cookText(entry: MealEntry, people: Map<String, FamilyPerson>, me: String?): String? {
    if (!entry.kind.cooks) return null
    val cook = entry.cookId?.let(people::get) ?: return null
    return cookText(cook, cook.id == me)
}

@Composable
internal fun cookText(cook: FamilyPerson, isMe: Boolean): String =
    if (isMe) stringResource(Res.string.meals_cook_you) else stringResource(Res.string.meals_cook_person, cook.name)

/** The kind's own quiet line ("🥡 Hämtmat"), unless the title already is the kind (nothing was typed in). */
@Composable
internal fun KindLine(entry: MealEntry) {
    val label = kindLabel(entry.kind)?.let { stringResource(it) } ?: return
    if (entry.title != label) KindText(entry.kind, label)
}

/**
 * The meal's [text]; takeaway or no cooking saved without a text ("Hämtmat") gets the kind's icon before it. The icon is
 * never the only sign: the words are always there too (here or in [KindLine]).
 */
@Composable
internal fun MealTitle(entry: MealEntry, text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val label = kindLabel(entry.kind)?.let { stringResource(it) }
    if (label != null && entry.title == label) {
        KindText(entry.kind, text, style, MaterialTheme.colorScheme.onSurface, modifier)
    } else {
        Text(text, style = style, modifier = modifier)
    }
}

@Composable
private fun KindText(
    kind: MealKind,
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        kindIcon(kind)?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Text(text, style = style, color = color)
    }
}

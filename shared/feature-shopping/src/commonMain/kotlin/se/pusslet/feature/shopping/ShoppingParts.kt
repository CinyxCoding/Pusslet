package se.pusslet.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.Aisle
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.ShoppingText
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * The list's header (UX plan, Handla 1): "Veckohandling ▾" opens the list menu, the share icon sends the list as text, the
 * sub line "5 kvar · ICA Nära Lindvägen ▾" chooses the store and a tonal "Jag handlar nu" shows while something is left.
 */
@Composable
internal fun ListHeader(state: ShoppingUiState, actions: ShoppingActions, modifier: Modifier = Modifier) {
    val list = state.list
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ListTitle(state, actions) }
            if (list != null && state.openCount > 0) {
                val labels = Aisle.entries.associateWith { stringResource(Labels.aisle(it)) }
                IconButton(onClick = { actions.onShare(ShoppingText.share(list.name, state.groups) { labels.getValue(it) }, list.name) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(Res.string.shopping_share))
                }
            }
        }
        val subtitle = subtitle(state)
        if (subtitle != null || state.canStartShopping) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(Modifier.weight(1f)) {
                    if (subtitle != null) {
                        SubLine(subtitle, chooseStore = list != null && state.canManage) { actions.onDialog(ListDialog.PickStore) }
                    }
                }
                if (state.canStartShopping) {
                    FilledTonalButton(onClick = actions.onStoreMode, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(Res.string.shopping_store_mode))
                    }
                }
            }
        }
    }
}

/** "5 kvar · ICA Nära Lindvägen". Managers always see the store part, as "Välj butik" when none is chosen. */
@Composable
private fun subtitle(state: ShoppingUiState): String? {
    if (state.list == null) return null
    // A list with nothing on it says so once, in the empty state below.
    val count = when {
        state.isEmpty -> null
        state.allBought -> stringResource(Res.string.shopping_nothing_left)
        else -> stringResource(Res.string.shopping_left_count, state.openCount)
    }
    val store = state.store?.name ?: stringResource(Res.string.shopping_menu_choose_store).takeIf { state.canManage }
    return if (count != null && store != null) stringResource(Res.string.shopping_subtitle_store, count, store) else count ?: store
}

@Composable
private fun SubLine(text: String, chooseStore: Boolean, onChooseStore: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val modifier = if (chooseStore) {
        Modifier.clickable(
            onClickLabel = stringResource(Res.string.shopping_menu_choose_store),
            role = Role.DropdownList,
            onClick = onChooseStore,
        )
    } else {
        Modifier
    }
    Row(modifier.heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f, fill = false))
        if (chooseStore) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = color)
    }
}

/** "Veckohandling ▾": the lists, "Handla till veckan", "Ny lista…", "Byt namn" and "Ta bort" (the last three for parents). */
@Composable
private fun ListTitle(state: ShoppingUiState, actions: ShoppingActions) {
    var open by remember { mutableStateOf(false) }
    val list = state.list
    val hasMenu = state.canManage || state.lists.size > 1 || state.canShopForWeek
    val title = list?.name ?: stringResource(Res.string.nav_shopping)
    val interaction = if (hasMenu) {
        Modifier.clickable(onClickLabel = stringResource(Res.string.shopping_pick_list), role = Role.DropdownList) { open = true }
    } else {
        Modifier
    }
    Row(
        interaction.heightIn(min = Spacing.touchTarget).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hasMenu) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ListMenuItems(state, actions, onDismiss = { open = false })
    }
}

@Composable
private fun ListMenuItems(state: ShoppingUiState, actions: ShoppingActions, onDismiss: () -> Unit) {
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }
    if (state.lists.size > 1) {
        state.lists.forEach { l ->
            val isSelected = l.id == state.list?.id
            DropdownMenuItem(
                text = { Text(l.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    if (isSelected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(MenuIconSize))
                },
                onClick = { run { actions.onSelectList(l.id) } },
                modifier = Modifier.semantics { selected = isSelected },
            )
        }
        HorizontalDivider()
    }
    if (state.canShopForWeek) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.shopping_from_meals)) },
            onClick = { run(actions.onFromMealPlan) },
        )
    }
    if (state.canManage) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.shopping_menu_new_list)) },
            onClick = { run { actions.onDialog(ListDialog.Create) } },
        )
        if (state.list != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.shopping_menu_rename)) },
                onClick = { run { actions.onDialog(ListDialog.Rename) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.action_delete)) },
                onClick = { run { actions.onDialog(ListDialog.Delete) } },
            )
        }
    }
}

/** "Sara och Johan handlar nu", "Sara handlar (Apoteket) · Byt": everyone shopping right now on one quiet row. */
@Composable
internal fun ShoppersLine(row: ShoppersRow, onSwitch: (String) -> Unit, modifier: Modifier = Modifier) {
    val who = joinNames(row.names)
    val text = if (row.elsewhere.isEmpty()) {
        when (row.names.size) {
            0 -> stringResource(Res.string.shopping_someone_shopping)
            1 -> stringResource(Res.string.shopping_shopper_now, who)
            else -> stringResource(Res.string.shopping_shoppers_now, who)
        }
    } else {
        val lists = row.elsewhere.joinToString(", ") { it.name }
        when (row.names.size) {
            0 -> stringResource(Res.string.shopping_someone_at, lists)
            1 -> stringResource(Res.string.shopping_shopper_at, who, lists)
            else -> stringResource(Res.string.shopping_shoppers_at, who, lists)
        }
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = color, modifier = Modifier.size(SmallIconSize))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        row.elsewhere.firstOrNull()?.let { target ->
            val description = stringResource(Res.string.shopping_switch_to, target.name)
            TextButton(onClick = { onSwitch(target.id) }, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(Res.string.shopping_switch))
            }
        }
    }
}

/** "Tacos och Lasagne saknas på listan" with "Handla till veckan"; "Inte nu" waves it away until other recipes are planned. */
@Composable
internal fun WeekMissingCard(missing: WeekMissing, onShop: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = if (missing.titles.size in 1..MAX_NAMED_RECIPES) {
        stringResource(Res.string.shopping_week_missing_named, joinNames(missing.titles))
    } else {
        stringResource(Res.string.shopping_week_missing_count, missing.meals.size)
    }
    Banner(
        text = text,
        modifier = modifier,
        kind = BannerKind.Warning,
        icon = PussletIcons.Restaurant,
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.shopping_suggestions_dismiss)) }
            TextButton(onClick = onShop) { Text(stringResource(Res.string.shopping_from_meals)) }
        },
    )
}

/**
 * "Brukar ni köpa: Mjölk, Bröd ⌄" (UX plan, Handla 3): one row while folded; unfolded, a chip per suggestion that adds it,
 * with × to wave that one away until it is bought again.
 */
@Composable
internal fun SuggestionsRow(
    suggestions: List<StapleSuggestion>,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onAdd: (StapleSuggestion) -> Unit,
    onDismiss: (StapleSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val toggleLabel = stringResource(if (expanded) Res.string.shopping_suggestions_hide else Res.string.shopping_suggestions_show)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .clickable(onClickLabel = toggleLabel, role = Role.Button) { onExpand(!expanded) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.shopping_suggestions_line, suggestions.joinToString(", ") { it.name }),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = color)
        }
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                suggestions.forEach { s -> SuggestionChip(s, onAdd = { onAdd(s) }, onDismiss = { onDismiss(s) }) }
            }
        }
    }
}

@Composable
private fun SuggestionChip(suggestion: StapleSuggestion, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val addLabel = stringResource(Res.string.shopping_suggestion_add, suggestion.name)
    val dismissLabel = stringResource(Res.string.shopping_suggestion_dismiss, suggestion.name)
    InputChip(
        selected = false,
        onClick = onAdd,
        label = { Text(suggestion.name) },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = dismissLabel,
                modifier = Modifier
                    .size(InputChipDefaults.AvatarSize)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(Spacing.xs),
            )
        },
        modifier = Modifier.semantics {
            contentDescription = addLabel
            customActions = listOf(
                CustomAccessibilityAction(dismissLabel) {
                    onDismiss()
                    true
                },
            )
        },
    )
}

@Composable
internal fun AisleHeader(group: AisleGroup, modifier: Modifier = Modifier) {
    Text(
        stringResource(Labels.aisle(group.aisle)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Spacing.md, bottom = Spacing.xxs).semantics { heading() },
    )
}

/** "Avbockat (3)" with "Rensa avbockade" and a chevron that shows or hides the ticked-off items. */
@Composable
internal fun CheckedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.shopping_checked_count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        SmallAction(stringResource(Res.string.shopping_clear_checked), onClear)
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) Res.string.shopping_hide_checked_a11y else Res.string.shopping_show_checked_a11y,
                ),
            )
        }
    }
}

/** Up to two recipe names fit the card ("Tacos och Lasagne saknas på listan"); more become a count. */
private const val MAX_NAMED_RECIPES = 2
private val MenuIconSize = 24.dp
private val SmallIconSize = 20.dp

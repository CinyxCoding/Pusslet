package se.pusslet.feature.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.ShoppingText
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.core.units.Units
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonAvatar
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import se.pusslet.ui.theme.StoreModeItemStyle

@Composable
fun ShoppingRoute(
    onOpenStoreMode: () -> Unit,
    onOpenStores: () -> Unit,
    onOpenPantry: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: ShoppingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.mealPlanPreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val platform = LocalPlatformActions.current
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var unknownBarcode by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(ListDialog.None) }
    var showMealPlan by rememberSaveable { mutableStateOf(false) }

    ShoppingMessages(viewModel, snackbar, onUnknownProduct = { unknownBarcode = it })

    Box(Modifier.fillMaxSize()) {
        ShoppingScreen(
            state = state,
            contentPadding = contentPadding,
            actions = ShoppingActions(
                onSelectList = viewModel::selectList,
                onAdd = viewModel::add,
                onScan = { platform.scanCode(viewModel::scanned) },
                onToggle = { item, checked -> viewModel.setChecked(item, checked) },
                onEdit = { editing = it },
                onAddSuggestion = viewModel::addSuggestion,
                onDismissSuggestions = viewModel::dismissSuggestions,
                onClearChecked = viewModel::clearChecked,
                onStoreMode = onOpenStoreMode,
                onFromMealPlan = {
                    showMealPlan = true
                    viewModel.previewMealPlan(0)
                },
                onShare = { text, subject -> platform.shareText(text, subject) },
                onDialog = { dialog = it },
                onOpenStores = onOpenStores,
                onOpenPantry = onOpenPantry,
            ),
        )
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + SNACKBAR_ABOVE_FAB),
        )
    }

    editing?.let { item ->
        ItemEditorSheet(
            item = item,
            people = state.people,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDelete = {
                viewModel.remove(item)
                editing = null
            },
        )
    }
    unknownBarcode?.let { code ->
        UnknownProductDialog(
            barcode = code,
            onDismiss = { unknownBarcode = null },
            onAdd = { name ->
                viewModel.addNamedProduct(code, name)
                unknownBarcode = null
            },
        )
    }
    if (showMealPlan) {
        MealPlanSheet(
            preview = preview,
            usePantry = state.usePantry,
            onPickWeek = viewModel::previewMealPlan,
            onConfirm = {
                viewModel.generateFromMealPlan()
                showMealPlan = false
            },
            onDismiss = {
                viewModel.dismissMealPlanPreview()
                showMealPlan = false
            },
        )
    }
    ListDialogs(
        dialog = dialog,
        state = state,
        onDismiss = { dialog = ListDialog.None },
        onCreate = viewModel::createList,
        onRename = viewModel::renameList,
        onDelete = viewModel::deleteList,
        onPickStore = viewModel::setStore,
        onOpenStores = onOpenStores,
    )
}

/** Turns one-off messages from the view model into snackbars (with "Ångra" where it makes sense). */
@Composable
internal fun ShoppingMessages(
    viewModel: ShoppingViewModel,
    snackbar: SnackbarHostState,
    onUnknownProduct: (String) -> Unit,
    onAllDone: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // A new message replaces the one on screen; launched so collecting never waits for the user.
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                when (message) {
                    is ShoppingMessage.Added -> {
                        val text = getString(Res.string.shopping_added, message.outcome.names.joinToString(", "))
                        val result = snackbar.showSnackbar(text, getString(Res.string.action_undo), duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoAdd(message.outcome)
                    }
                    is ShoppingMessage.AlreadyOnList ->
                        snackbar.showSnackbar(getString(Res.string.shopping_already_on_list, message.names.joinToString(", ")))
                    is ShoppingMessage.Removed -> {
                        val text = getString(Res.string.shopping_removed, message.item.name)
                        val result = snackbar.showSnackbar(text, getString(Res.string.action_undo), duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(message.item)
                    }
                    is ShoppingMessage.InCart -> {
                        val text = getString(Res.string.shopping_in_cart_toast, message.item.name)
                        val result = snackbar.showSnackbar(text, getString(Res.string.action_undo), duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(message.item)
                    }
                    ShoppingMessage.AllDone -> {
                        onAllDone()
                        snackbar.showSnackbar(getString(Res.string.shopping_all_done))
                    }
                    is ShoppingMessage.UnknownProduct -> onUnknownProduct(message.barcode)
                    is ShoppingMessage.Generated -> snackbar.showSnackbar(
                        if (message.plan.isEmpty) {
                            getString(Res.string.shopping_generated_nothing)
                        } else {
                            getString(Res.string.shopping_generated)
                        },
                    )
                    ShoppingMessage.Failed -> snackbar.showSnackbar(getString(Res.string.error_generic))
                }
            }
        }
    }
}

/** Callbacks from the stateless list screen. */
data class ShoppingActions(
    val onSelectList: (String) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onScan: () -> Unit = {},
    val onToggle: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    val onEdit: (ShoppingItem) -> Unit = {},
    val onAddSuggestion: (StapleSuggestion) -> Unit = {},
    val onDismissSuggestions: () -> Unit = {},
    val onClearChecked: () -> Unit = {},
    val onStoreMode: () -> Unit = {},
    val onFromMealPlan: () -> Unit = {},
    val onShare: (text: String, subject: String) -> Unit = { _, _ -> },
    val onDialog: (ListDialog) -> Unit = {},
    val onOpenStores: () -> Unit = {},
    val onOpenPantry: () -> Unit = {},
)

@Composable
fun ShoppingScreen(
    state: ShoppingUiState,
    contentPadding: PaddingValues = PaddingValues(),
    actions: ShoppingActions = ShoppingActions(),
) {
    if (state.loading) {
        LoadingBox()
        return
    }
    var showChecked by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.lg + contentPadding.calculateTopPadding(),
            bottom = Spacing.xxl * 3 + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "header") { Header(state, actions) }
        if (state.lists.size > 1 || state.canManage) item(key = "lists") { ListChips(state, actions) }
        items(state.shoppers, key = { "shopper-${it.memberId}" }) { ShopperBanner(it) }
        item(key = "add") { AddField(actions) }
        item(key = "primary") { PrimaryActions(state, actions) }
        if (state.suggestions.isNotEmpty()) item(key = "suggestions") { Suggestions(state, actions) }
        if (state.isEmpty) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(Res.string.shopping_empty_title),
                    message = stringResource(Res.string.shopping_empty_body),
                )
            }
        }
        state.groups.forEach { group ->
            item(key = "aisle-${group.aisle}") { AisleHeader(group) }
            items(group.items, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    addedBy = item.addedBy?.let(state.people::get),
                    onToggle = { actions.onToggle(item, it) },
                    onEdit = { actions.onEdit(item) },
                )
            }
        }
        if (state.checked.isNotEmpty()) {
            item(key = "checked-header") {
                CheckedHeader(
                    count = state.checked.size,
                    expanded = showChecked,
                    onToggle = { showChecked = !showChecked },
                    onClear = actions.onClearChecked,
                )
            }
            if (showChecked) {
                items(state.checked, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        addedBy = null,
                        onToggle = { actions.onToggle(item, it) },
                        onEdit = { actions.onEdit(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(state: ShoppingUiState, actions: ShoppingActions) {
    var menu by remember { mutableStateOf(false) }
    val list = state.list
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                list?.name ?: stringResource(Res.string.nav_shopping),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                list == null -> null
                state.openCount == 0 -> stringResource(Res.string.shopping_nothing_left)
                else -> stringResource(Res.string.shopping_left_count, state.openCount)
            }
            if (subtitle != null) {
                val store = state.store?.name
                Text(
                    if (store != null) stringResource(Res.string.shopping_subtitle_store, subtitle, store) else subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (list != null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.shopping_more))
                }
                OverflowMenu(state, actions, expanded = menu, onDismiss = { menu = false })
            }
        }
    }
}

@Composable
private fun OverflowMenu(state: ShoppingUiState, actions: ShoppingActions, expanded: Boolean, onDismiss: () -> Unit) {
    val list = state.list ?: return
    val shareTitle = list.name
    val labels = Aisle.entries.associateWith { stringResource(Labels.aisle(it)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.shopping_share)) },
            enabled = state.openCount > 0,
            onClick = {
                onDismiss()
                actions.onShare(ShoppingText.share(shareTitle, state.groups) { labels.getValue(it) }, shareTitle)
            },
        )
        if (state.checked.isNotEmpty()) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.shopping_clear_checked)) }, onClick = {
                onDismiss()
                actions.onClearChecked()
            })
        }
        if (state.canManage) {
            DropdownMenuItem(
                text = {
                    Text(
                        state.store?.let { stringResource(Res.string.shopping_menu_store, it.name) }
                            ?: stringResource(Res.string.shopping_menu_choose_store),
                    )
                },
                onClick = {
                    onDismiss()
                    actions.onDialog(ListDialog.PickStore)
                },
            )
            DropdownMenuItem(text = { Text(stringResource(Res.string.shopping_stores)) }, onClick = {
                onDismiss()
                actions.onOpenStores()
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.pantry_title)) }, onClick = {
                onDismiss()
                actions.onOpenPantry()
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(Res.string.shopping_rename_list)) }, onClick = {
                onDismiss()
                actions.onDialog(ListDialog.Rename)
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.shopping_delete_list)) }, onClick = {
                onDismiss()
                actions.onDialog(ListDialog.Delete)
            })
        }
    }
}

@Composable
private fun ListChips(state: ShoppingUiState, actions: ShoppingActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.lists.forEach { list ->
            FilterChip(
                selected = list.id == state.list?.id,
                onClick = { actions.onSelectList(list.id) },
                label = { Text(list.name, maxLines = 1) },
            )
        }
        if (state.canManage) {
            AssistChip(
                onClick = { actions.onDialog(ListDialog.Create) },
                label = { Text(stringResource(Res.string.shopping_new_list)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ShopperBanner(shopper: Shopper) {
    val name = shopper.person?.name
    Banner(
        text = if (name != null) {
            stringResource(Res.string.shopping_shopper_now, name)
        } else {
            stringResource(Res.string.shopping_someone_shopping)
        },
    )
}

@Composable
private fun AddField(actions: ShoppingActions) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (text.isNotBlank()) {
            actions.onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.shopping_add_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            if (text.isBlank()) {
                IconButton(onClick = actions.onScan) {
                    Icon(PussletIcons.Barcode, contentDescription = stringResource(Res.string.shopping_scan))
                }
            } else {
                IconButton(onClick = ::submit) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.shopping_add_action))
                }
            }
        },
    )
}

@Composable
private fun PrimaryActions(state: ShoppingUiState, actions: ShoppingActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(onClick = actions.onStoreMode, enabled = state.list != null, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
            Text(stringResource(Res.string.shopping_store_mode))
        }
        if (state.list?.kind != ListKind.Other) {
            OutlinedButton(onClick = actions.onFromMealPlan, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(PussletIcons.Restaurant, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                Text(stringResource(Res.string.shopping_from_meals))
            }
        }
    }
}

@Composable
private fun Suggestions(state: ShoppingUiState, actions: ShoppingActions) {
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.shopping_suggestions_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            SmallAction(stringResource(Res.string.shopping_suggestions_dismiss), actions.onDismissSuggestions)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            state.suggestions.forEach { s ->
                val description = stringResource(Res.string.shopping_suggestion_add, s.name)
                SuggestionChip(
                    onClick = { actions.onAddSuggestion(s) },
                    label = { Text(s.name) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
}

@Composable
private fun AisleHeader(group: AisleGroup) {
    Text(
        stringResource(Labels.aisle(group.aisle)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
    )
}

@Composable
private fun CheckedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.padding(top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.shopping_in_cart_count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        SmallAction(stringResource(if (expanded) Res.string.shopping_hide_checked else Res.string.shopping_show_checked), onToggle)
        SmallAction(stringResource(Res.string.shopping_clear_checked), onClear)
    }
}

/** "2 l · laktosfri" */
internal fun itemDetails(item: ShoppingItem): String? =
    listOfNotNull(item.amount?.let { Units.format(it) }, item.note.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }

/**
 * One line on the list: tap anywhere to tick off (with a subtle haptic tick), the pen to edit, long-press also edits.
 * [large] is used in store mode ("stor text").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemRow(
    item: ShoppingItem,
    addedBy: FamilyPerson?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val checkedLabel = stringResource(if (item.checked) Res.string.shopping_state_in_cart else Res.string.shopping_state_to_buy)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (large) LARGE_ROW else Spacing.primaryTouchTarget)
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = stringResource(if (item.checked) Res.string.shopping_put_back else Res.string.shopping_tick_off),
                onLongClickLabel = stringResource(Res.string.action_edit),
                onLongClick = onEdit,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle(!item.checked)
                },
            )
            .semantics { stateDescription = checkedLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
            val style = if (large) StoreModeItemStyle else MaterialTheme.typography.bodyLarge
            Text(
                item.name,
                style = style.dimIf(item.checked),
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            )
            itemDetails(item)?.let { details ->
                Text(
                    details,
                    style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (addedBy != null && !large) PersonAvatar(addedBy, size = AVATAR)
        if (!large) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.shopping_edit_item_named, item.name))
            }
        }
    }
}

@Composable
private fun TextStyle.dimIf(dim: Boolean): TextStyle = if (dim) copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else this

private val SNACKBAR_ABOVE_FAB: Dp = 80.dp
private val LARGE_ROW: Dp = 72.dp
private val AVATAR: Dp = 24.dp

package se.pusslet.feature.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
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
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.SelectionColors
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
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var unknownBarcode by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf(ListDialog.None) }
    var showMealPlan by rememberSaveable { mutableStateOf(false) }

    // The add field (UX plan 1.2): the + opens it above the keyboard; an empty list shows it from the start. Coming back
    // to the tab shows the + again (not saved), while unsent text is kept for the next time the field opens.
    var addOpen by remember { mutableStateOf(false) }
    var addText by rememberSaveable { mutableStateOf("") }
    var addFocusRequested by remember { mutableStateOf(false) }
    var addFocused by remember { mutableStateOf(false) }
    val showAdd = addOpen || (!state.loading && state.isEmpty)

    fun closeAdd() {
        addOpen = false
        addText = ""
        focusManager.clearFocus()
    }

    RegisterAddButton(label = stringResource(Res.string.shopping_add_hint), enabled = !showAdd) {
        addOpen = true
        addFocusRequested = true
    }
    // Back closes the keyboard first; an empty field then goes away with it.
    OnKeyboardHidden(enabled = showAdd) { if (addText.isBlank()) closeAdd() }
    ShoppingMessages(viewModel, snackbar, onUnknownProduct = { unknownBarcode = it })

    Box(Modifier.fillMaxSize()) {
        ShoppingScreen(
            state = state,
            contentPadding = contentPadding,
            actions = ShoppingActions(
                onSelectList = viewModel::selectList,
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
        val bottom = contentPadding.calculateBottomPadding()
        // Above the bottom bar, or above the keyboard when it is taller (the bar's height counts as already used).
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottom)
                .consumeWindowInsets(PaddingValues(bottom = bottom))
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // "Tillagt: Mjölk · Ångra" shows right above the field, otherwise above the +.
            SnackbarHost(snackbar, Modifier.padding(bottom = if (showAdd) 0.dp else SNACKBAR_ABOVE_FAB))
            if (showAdd) {
                AddItemBar(
                    text = addText,
                    onTextChange = { addText = it },
                    focusRequested = addFocusRequested,
                    onFocusHandled = { addFocusRequested = false },
                    onFocusChange = { focused ->
                        when {
                            focused -> addOpen = true
                            addFocused && addText.isBlank() -> addOpen = false
                        }
                        addFocused = focused
                    },
                    onAdd = viewModel::add,
                    onScan = { platform.scanCode(viewModel::scanned) },
                    onClose = ::closeAdd,
                )
            }
        }
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

/** Calls [onHidden] when the keyboard goes away (for example with Back) after it was shown while [enabled]. */
@Composable
private fun OnKeyboardHidden(enabled: Boolean, onHidden: () -> Unit) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val visible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val latest by rememberUpdatedState(onHidden)
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(visible, enabled) {
        when {
            !enabled -> seen = false
            visible -> seen = true
            seen -> {
                seen = false
                latest()
            }
        }
    }
}

/**
 * The list's own add field, right above the keyboard. It stays open for several items: "Klar" on the keyboard adds and
 * keeps the keyboard up. Parsing and merging ("mjölk" twice is one row) happen in the view model.
 */
@Composable
private fun AddItemBar(
    text: String,
    onTextChange: (String) -> Unit,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onScan: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            focus.requestFocus()
            keyboard?.show()
            onFocusHandled()
        }
    }

    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            onTextChange("")
        }
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = BAR_ELEVATION) {
        Row(
            Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { onFocusChange(it.isFocused) },
                placeholder = { Text(stringResource(Res.string.shopping_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    if (text.isBlank()) {
                        IconButton(onClick = onScan) {
                            Icon(PussletIcons.Barcode, contentDescription = stringResource(Res.string.shopping_scan))
                        }
                    } else {
                        IconButton(onClick = ::submit) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.shopping_add_action))
                        }
                    }
                },
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
            }
        }
    }
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
                        // "Mjölk i vagnen" in the store, "Mjölk bockad" on the planning list.
                        val label = if (message.inStore) Res.string.shopping_in_cart_toast else Res.string.shopping_ticked
                        val text = getString(label, message.item.name)
                        val result = snackbar.showSnackbar(text, getString(Res.string.action_undo), duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(message.item)
                    }
                    is ShoppingMessage.Cleared -> {
                        val items = message.items
                        val text = if (items.size == 1) {
                            getString(Res.string.shopping_removed, items.single().name)
                        } else {
                            getString(Res.string.shopping_cleared, items.size)
                        }
                        val result = snackbar.showSnackbar(text, getString(Res.string.action_undo), duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreCleared(items)
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

/** Callbacks from the stateless list screen. Adding goes through the round + and the add field of [ShoppingRoute]. */
data class ShoppingActions(
    val onSelectList: (String) -> Unit = {},
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
    val bottom = contentPadding.calculateBottomPadding()
    val showStoreMode = state.canStartShopping
    val showFromMeals = state.list?.kind != ListKind.Other
    LazyColumn(
        // With the keyboard up, the list ends above it (and the add field), so every row can still be scrolled to.
        modifier = Modifier.fillMaxSize().consumeWindowInsets(PaddingValues(bottom = bottom)).imePadding(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.lg + contentPadding.calculateTopPadding(),
            bottom = Spacing.listBottomInset + bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "header") { Header(state, actions) }
        if (state.lists.size > 1 || state.canManage) item(key = "lists") { ListChips(state, actions) }
        items(state.shoppers, key = { "shopper-${it.memberId}" }) { ShopperBanner(it) }
        if (showStoreMode || showFromMeals) {
            item(key = "primary") { PrimaryActions(actions, showStoreMode = showStoreMode, showFromMeals = showFromMeals) }
        }
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
            // "5 kvar · ICA Nära". A list with nothing on it says so once, in the empty state below.
            val count = when {
                list == null || state.isEmpty -> null
                state.allBought -> stringResource(Res.string.shopping_nothing_left)
                else -> stringResource(Res.string.shopping_left_count, state.openCount)
            }
            val store = state.store?.name?.takeIf { list != null }
            val subtitle = if (count != null && store != null) {
                stringResource(Res.string.shopping_subtitle_store, count, store)
            } else {
                count ?: store
            }
            if (subtitle != null) {
                Text(
                    subtitle,
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
                colors = SelectionColors.filterChip(),
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

/** "Jag handlar nu" (only while something is left to buy) and "Handla till veckan". */
@Composable
private fun PrimaryActions(actions: ShoppingActions, showStoreMode: Boolean, showFromMeals: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showStoreMode) {
            Button(onClick = actions.onStoreMode, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                Text(stringResource(Res.string.shopping_store_mode))
            }
        }
        if (showFromMeals) {
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

/** "Avbockat (3)" with "Rensa avbockade" and a chevron that shows or hides the ticked-off items. */
@Composable
private fun CheckedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.padding(top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
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

/** "2 l · laktosfri" */
internal fun itemDetails(item: ShoppingItem): String? =
    listOfNotNull(item.amount?.let { Units.format(it) }, item.note.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }

/**
 * One line on the list, like on paper: the checkbox ticks off (with a subtle haptic tick) and the text opens editing.
 * [large] is store mode ("stor text"): there the whole row ticks off and a long press edits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemRow(
    item: ShoppingItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val checkedLabel = stringResource(if (item.checked) Res.string.shopping_state_in_cart else Res.string.shopping_state_to_buy)
    val toggleLabel = stringResource(if (item.checked) Res.string.shopping_put_back else Res.string.shopping_tick_off)
    val editLabel = stringResource(Res.string.action_edit)
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
            ItemText(item, large = true, modifier = Modifier.weight(1f).padding(vertical = Spacing.xs))
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { toggle() },
                modifier = Modifier.semantics {
                    contentDescription = item.name
                    stateDescription = checkedLabel
                    onClick(label = toggleLabel) {
                        toggle()
                        true
                    }
                },
            )
            ItemText(
                item,
                large = false,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.primaryTouchTarget)
                    .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onEdit)
                    .padding(Spacing.xs),
            )
        }
    }
}

@Composable
private fun ItemText(item: ShoppingItem, large: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
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
}

@Composable
private fun TextStyle.dimIf(dim: Boolean): TextStyle = if (dim) copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else this

private val SNACKBAR_ABOVE_FAB: Dp = 80.dp
private val BAR_ELEVATION: Dp = 3.dp
private val LARGE_ROW: Dp = 72.dp

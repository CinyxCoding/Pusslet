package se.pusslet.feature.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.FirstTimeHint
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.components.RegisterAddButton
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Handla (UX plan, Handla): the list first. The header holds the list menu, sharing, the store and "Jag handlar nu"; the
 * round + opens the add field above the keyboard; "Butiker och det vi har hemma" is the last row ([onOpenStores]).
 */
@Composable
fun ShoppingRoute(
    onOpenStoreMode: () -> Unit,
    onOpenStores: () -> Unit,
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
    // "Klar – töm vagnen" in store mode ends here, as "6 varor rensade · Ångra".
    ShoppingMessages(viewModel, snackbar, onUnknownProduct = { unknownBarcode = it }, showStoreReceipts = true)

    Box(Modifier.fillMaxSize()) {
        ShoppingScreen(
            state = state,
            contentPadding = contentPadding,
            actions = ShoppingActions(
                onSelectList = viewModel::selectList,
                onToggle = { item, checked -> viewModel.setChecked(item, checked) },
                onEdit = { editing = it },
                onRemove = viewModel::remove,
                onAddSuggestion = viewModel::addSuggestion,
                onDismissSuggestion = viewModel::dismissSuggestion,
                onClearChecked = viewModel::clearChecked,
                onStoreMode = onOpenStoreMode,
                onFromMealPlan = {
                    showMealPlan = true
                    viewModel.previewMealPlan(0)
                },
                onDismissWeekMissing = viewModel::dismissWeekMissing,
                onShare = { text, subject -> platform.shareText(text, subject) },
                onDialog = { dialog = it },
                onOpenStores = onOpenStores,
                onScan = { platform.scanCode(viewModel::scanned) },
                onDismissHint = viewModel::dismissMergeHint,
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

/**
 * Turns one-off messages from the view model into snackbars (with "Ångra" where it makes sense). [showStoreReceipts] is
 * for the list screen: it also shows "Klar – töm vagnen" from store mode once it is back on screen.
 */
@Composable
internal fun ShoppingMessages(
    viewModel: ShoppingViewModel,
    snackbar: SnackbarHostState,
    onUnknownProduct: (String) -> Unit,
    onAllDone: () -> Unit = {},
    showStoreReceipts: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel, showStoreReceipts) {
        merge(viewModel.messages, if (showStoreReceipts) viewModel.storeReceipts else emptyFlow()).collect { message ->
            // A new message replaces the one on screen; launched so collecting never waits for the user.
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { show(message, viewModel, snackbar, onUnknownProduct, onAllDone) }
        }
    }
}

private suspend fun show(
    message: ShoppingMessage,
    viewModel: ShoppingViewModel,
    snackbar: SnackbarHostState,
    onUnknownProduct: (String) -> Unit,
    onAllDone: () -> Unit,
) {
    val undo = getString(Res.string.action_undo)
    when (message) {
        is ShoppingMessage.Added -> {
            val text = getString(Res.string.shopping_added, message.outcome.names.joinToString(", "))
            if (snackbar.showUndo(text, undo, SnackbarDuration.Short)) viewModel.undoAdd(message.outcome)
        }
        is ShoppingMessage.AlreadyOnList ->
            snackbar.showSnackbar(getString(Res.string.shopping_already_on_list, message.names.joinToString(", ")))
        is ShoppingMessage.Removed -> {
            if (snackbar.showUndo(getString(Res.string.shopping_removed, message.item.name), undo)) viewModel.restore(message.item)
        }
        is ShoppingMessage.InCart -> {
            // "Mjölk i vagnen" in the store, "Mjölk bockad" on the planning list.
            val label = if (message.inStore) Res.string.shopping_in_cart_toast else Res.string.shopping_ticked
            if (snackbar.showUndo(getString(label, message.item.name), undo, SnackbarDuration.Short)) viewModel.restore(message.item)
        }
        is ShoppingMessage.Cleared -> {
            val items = message.items
            val text = if (items.size == 1) {
                getString(Res.string.shopping_removed, items.single().name)
            } else {
                getString(Res.string.shopping_cleared, items.size)
            }
            if (snackbar.showUndo(text, undo)) viewModel.restoreCleared(items)
        }
        // Store mode shows "Allt i vagnen!" as a card at the bottom, so no snackbar repeats it.
        ShoppingMessage.AllDone -> onAllDone()
        is ShoppingMessage.UnknownProduct -> onUnknownProduct(message.barcode)
        is ShoppingMessage.Generated -> snackbar.showSnackbar(
            if (message.plan.isEmpty) getString(Res.string.shopping_generated_nothing) else getString(Res.string.shopping_generated),
        )
        ShoppingMessage.Failed -> snackbar.showSnackbar(getString(Res.string.error_generic))
    }
}

/** Callbacks from the stateless list screen. Adding goes through the round + and the add field of [ShoppingRoute]. */
data class ShoppingActions(
    val onSelectList: (String) -> Unit = {},
    val onToggle: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    val onEdit: (ShoppingItem) -> Unit = {},
    /** TalkBack's "Ta bort" on a row (with "Ångra"). */
    val onRemove: (ShoppingItem) -> Unit = {},
    val onAddSuggestion: (StapleSuggestion) -> Unit = {},
    /** × on one suggestion chip. */
    val onDismissSuggestion: (StapleSuggestion) -> Unit = {},
    val onClearChecked: () -> Unit = {},
    val onStoreMode: () -> Unit = {},
    /** "Handla till veckan" (empty state, the "saknas på listan" card and the list menu). */
    val onFromMealPlan: () -> Unit = {},
    /** "Inte nu" on the "saknas på listan" card. */
    val onDismissWeekMissing: () -> Unit = {},
    val onShare: (text: String, subject: String) -> Unit = { _, _ -> },
    val onDialog: (ListDialog) -> Unit = {},
    /** "Butiker och det vi har hemma". */
    val onOpenStores: () -> Unit = {},
    /** "Skanna streckkod" in the empty state: the camera scanner, like the add field's barcode icon. */
    val onScan: () -> Unit = {},
    /** "Jag förstår" on the first-time hint. */
    val onDismissHint: () -> Unit = {},
)

/**
 * The planning list (UX plan, Handla 1–4): header, who is shopping, "saknas på listan", the items per aisle, "Brukar ni
 * köpa", "Avbockat (n)" and "Butiker och det vi har hemma". A ticked row stays struck through for [LINGER_MS] before it
 * moves to "Avbockat" (a short-lived set in this screen, one delay per item).
 */
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
    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    val lingering = remember { mutableStateMapOf<String, ShoppingItem>() }
    lingering.keys.toList().forEach { id ->
        key(id) {
            LaunchedEffect(id) {
                delay(LINGER_MS)
                lingering.remove(id)
            }
        }
    }
    val toggle: (ShoppingItem, Boolean) -> Unit = { item, checked ->
        if (checked) lingering[item.id] = item.copy(checked = true) else lingering.remove(item.id)
        actions.onToggle(item, checked)
    }
    val groups = withLingering(state.groups, lingering.values, state.store?.aisleOrder ?: Aisle.defaultOrder)
    val checked = state.checked.filter { it.id !in lingering }
    val bottom = contentPadding.calculateBottomPadding()
    LazyColumn(
        // With the keyboard up, the list ends above it (and the add field), so every row can still be scrolled to.
        modifier = Modifier.fillMaxSize().consumeWindowInsets(PaddingValues(bottom = bottom)).imePadding(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = Spacing.sm + contentPadding.calculateTopPadding(),
            bottom = Spacing.listBottomInset + bottom,
        ),
    ) {
        item(key = "header") { ListHeader(state, actions) }
        state.shoppersRow?.let { row -> item(key = "shoppers") { ShoppersLine(row, onSwitch = actions.onSelectList) } }
        val missing = state.weekMissing
        if (missing != null && !state.isEmpty) {
            item(key = "week-missing") {
                WeekMissingCard(
                    missing,
                    onShop = actions.onFromMealPlan,
                    onDismiss = actions.onDismissWeekMissing,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
        }
        if (state.showMergeHint) {
            item(key = "hint") {
                FirstTimeHint(
                    stringResource(Res.string.shopping_hint_merge),
                    onDismiss = actions.onDismissHint,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
        }
        if (state.isEmpty) {
            item(key = "empty") {
                EmptyList(showShopForWeek = state.canShopForWeek, onShopForWeek = actions.onFromMealPlan, onScan = actions.onScan)
            }
        }
        groups.forEach { group ->
            item(key = "aisle-${group.aisle}") { AisleHeader(group) }
            items(group.items, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    onToggle = { toggle(item, it) },
                    onEdit = { actions.onEdit(item) },
                    onRemove = { actions.onRemove(item) },
                )
            }
        }
        if (state.suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                SuggestionsRow(
                    suggestions = state.suggestions,
                    expanded = showSuggestions,
                    onExpand = { showSuggestions = it },
                    onAdd = actions.onAddSuggestion,
                    onDismiss = actions.onDismissSuggestion,
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
        }
        if (checked.isNotEmpty()) {
            item(key = "checked-header") {
                CheckedHeader(
                    count = checked.size,
                    expanded = showChecked,
                    onToggle = { showChecked = !showChecked },
                    onClear = actions.onClearChecked,
                )
            }
            if (showChecked) {
                items(checked, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = { toggle(item, it) },
                        onEdit = { actions.onEdit(item) },
                        onRemove = { actions.onRemove(item) },
                    )
                }
            }
        }
        if (state.canManage) {
            item(key = "stores") {
                NavRow(
                    stringResource(Res.string.shopping_stores_and_pantry),
                    actions.onOpenStores,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
        }
    }
}

/**
 * "Listan är tom" with how to type ("Skriv som vanligt: 2 l mjölk, bröd"), the one filled button "Handla till veckan"
 * (grocery lists only) and "Skanna streckkod" as a text button (UX plan 4.5).
 */
@Composable
private fun EmptyList(showShopForWeek: Boolean, onShopForWeek: () -> Unit, onScan: () -> Unit) {
    EmptyState(
        title = stringResource(Res.string.shopping_empty_title),
        message = stringResource(Res.string.shopping_empty_body),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (showShopForWeek) {
                    Button(onClick = onShopForWeek, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(PussletIcons.Restaurant, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(Res.string.shopping_from_meals))
                    }
                }
                TextButton(onClick = onScan, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                    Icon(PussletIcons.Barcode, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.shopping_scan))
                }
            }
        },
    )
}

/** How long a ticked row stays struck through in its aisle before it moves to "Avbockat". */
private const val LINGER_MS = 2_000L
private val SNACKBAR_ABOVE_FAB: Dp = 80.dp
private val BAR_ELEVATION: Dp = 3.dp

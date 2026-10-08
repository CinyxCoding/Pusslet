package se.pusslet.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletIcons
import se.pusslet.ui.format.formatPrice
import se.pusslet.ui.format.formatQuantity
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun ShoppingRoute(
    onStoreMode: (String) -> Unit,
    onPantry: () -> Unit,
    onStores: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: ShoppingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m -> showMessage(snackbar, m, viewModel::restore) }
    }
    Box(Modifier.fillMaxSize()) {
        ShoppingScreen(
            state = state,
            contentPadding = contentPadding,
            actions = ShoppingActions(
                onSelectList = viewModel::selectList,
                onAdd = viewModel::addText,
                onCheck = viewModel::setChecked,
                onSaveItem = viewModel::saveItem,
                onDeleteItem = viewModel::delete,
                onClearChecked = viewModel::clearChecked,
                onAddStaple = viewModel::addStaple,
                onDismissStaple = viewModel::dismissStaple,
                onStartShopping = viewModel::startShopping,
                onStopShopping = viewModel::stopShopping,
                onStoreMode = { state.list?.let { onStoreMode(it.id) } },
                onScan = { platform.scanCode { code -> viewModel.onBarcode(code) } },
                onShare = { scope.launch { platform.shareText(shareText(state)) } },
                onPantry = onPantry,
                onStores = onStores,
                onCreateList = viewModel::createList,
                onUpdateList = viewModel::updateList,
                onDeleteList = viewModel::deleteList,
                onNameBarcode = viewModel::nameUnknownBarcode,
                onDismissBarcode = viewModel::dismissUnknownBarcode,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 72.dp))
    }
}

internal suspend fun showMessage(snackbar: SnackbarHostState, m: ShoppingMessage, restore: (ShoppingItem) -> Unit) {
    when (m) {
        is ShoppingMessage.Deleted -> {
            val r = snackbar.showSnackbar(
                getString(Res.string.item_deleted, m.item.name),
                getString(Res.string.action_undo),
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) restore(m.item)
        }
        is ShoppingMessage.Checked -> {
            val r = snackbar.showSnackbar(
                getString(Res.string.item_checked, m.item.name),
                getString(Res.string.action_undo),
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) restore(m.item)
        }
        is ShoppingMessage.Scanned -> snackbar.showSnackbar(getString(Res.string.shopping_scan_added, m.name))
        is ShoppingMessage.AddedByOther -> snackbar.showSnackbar(getString(Res.string.shopping_added_by_other, m.person.name, m.name))
    }
}

/** Everything the screen can ask for, so previews and tests can pass no-ops. */
data class ShoppingActions(
    val onSelectList: (String) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onCheck: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    val onSaveItem: (ShoppingItem) -> Unit = {},
    val onDeleteItem: (ShoppingItem) -> Unit = {},
    val onClearChecked: () -> Unit = {},
    val onAddStaple: (StapleSuggestion) -> Unit = {},
    val onDismissStaple: (StapleSuggestion) -> Unit = {},
    val onStartShopping: () -> Unit = {},
    val onStopShopping: () -> Unit = {},
    val onStoreMode: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onPantry: () -> Unit = {},
    val onStores: () -> Unit = {},
    val onCreateList: (String, se.pusslet.core.model.ListKind) -> Unit = { _, _ -> },
    val onUpdateList: (se.pusslet.core.model.ShoppingList) -> Unit = {},
    val onDeleteList: (String) -> Unit = {},
    val onNameBarcode: (String) -> Unit = {},
    val onDismissBarcode: () -> Unit = {},
)

private enum class ListDialog { New, Settings }

@Composable
fun ShoppingScreen(state: ShoppingUiState, contentPadding: PaddingValues = PaddingValues(), actions: ShoppingActions = ShoppingActions()) {
    if (state.loading) {
        LoadingBox()
        return
    }
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var dialog by rememberSaveable { mutableStateOf<ListDialog?>(null) }
    var showChecked by rememberSaveable { mutableStateOf(false) }
    val list = state.list
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        ListBar(state, actions, onNewList = { dialog = ListDialog.New }, onListSettings = { dialog = ListDialog.Settings })
        if (list == null) {
            EmptyState(stringResource(Res.string.shopping_no_lists)) {
                Button(onClick = { dialog = ListDialog.New }) { Text(stringResource(Res.string.shopping_create_first)) }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    bottom = Spacing.xxl * 3 + contentPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                item(key = "add") { AddField(actions.onAdd, actions.onScan) }
                item(key = "actions") { ActionChips(state, actions) }
                state.shoppers.filter { !it.isMe }.forEach { s ->
                    item(key = "shopper-${s.person.id}") { Banner(stringResource(Res.string.shopping_presence, s.person.name)) }
                }
                if (state.iAmShopping) item(key = "me-shopping") { Banner(stringResource(Res.string.shopping_presence_me)) }
                if (state.staples.isNotEmpty()) item(key = "staples") { Staples(state.staples, actions) }
                if (state.groups.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(stringResource(if (state.checked.isEmpty()) Res.string.empty_shopping else Res.string.shopping_all_done))
                    }
                }
                state.groups.forEach { group ->
                    item(key = "aisle-${group.aisle.name}") { AisleHeader(stringResource(Labels.aisle(group.aisle))) }
                    items(group.items, key = { it.id }) { item ->
                        ItemRow(item, state, onCheck = { actions.onCheck(item, it) }, onEdit = { editing = item })
                    }
                }
                state.total?.let { total ->
                    item(key = "total") {
                        Text(
                            stringResource(Res.string.shopping_total, formatPrice(total)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
                if (state.checked.isNotEmpty()) {
                    item(key = "checked-header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showChecked = !showChecked }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(Res.string.shopping_in_cart, state.checked.size), modifier = Modifier.fillMaxWidth())
                            }
                            TextButton(onClick = actions.onClearChecked) { Text(stringResource(Res.string.shopping_clear_checked)) }
                        }
                    }
                    if (showChecked) {
                        items(state.checked, key = { it.id }) { item ->
                            ItemRow(item, state, onCheck = { actions.onCheck(item, it) }, onEdit = { editing = item })
                        }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        ItemSheet(
            item = item,
            onDismiss = { editing = null },
            onSave = {
                actions.onSaveItem(it)
                editing = null
            },
            onDelete = {
                actions.onDeleteItem(item)
                editing = null
            },
        )
    }
    when (dialog) {
        ListDialog.New -> NewListDialog(onCreate = { name, kind ->
            actions.onCreateList(name, kind)
            dialog = null
        }, onDismiss = { dialog = null })
        ListDialog.Settings -> list?.let { l ->
            ListSettingsDialog(l, state.stores, onSave = {
                actions.onUpdateList(it)
                dialog = null
            }, onDelete = {
                actions.onDeleteList(l.id)
                dialog = null
            }, onDismiss = { dialog = null })
        }
        null -> Unit
    }
    state.unknownBarcode?.let { code -> UnknownBarcodeDialog(code, actions.onNameBarcode, actions.onDismissBarcode) }
}

@Composable
private fun ListBar(state: ShoppingUiState, actions: ShoppingActions, onNewList: () -> Unit, onListSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(state.lists, key = { it.id }) { l ->
                FilterChip(selected = l.id == state.list?.id, onClick = { actions.onSelectList(l.id) }, label = { Text(l.name) })
            }
            if (state.canManage) {
                item {
                    AssistChip(
                        onClick = onNewList,
                        label = { Text(stringResource(Res.string.shopping_new_list)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
        if (state.list != null) {
            Box {
                IconButton(onClick = {
                    menu = true
                }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.shopping_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    fun item(label: org.jetbrains.compose.resources.StringResource, action: () -> Unit) = label to action
                    val entries = buildList {
                        add(item(Res.string.shopping_share, actions.onShare))
                        add(item(Res.string.shopping_pantry, actions.onPantry))
                        if (state.canManage) {
                            add(item(Res.string.shopping_stores, actions.onStores))
                            add(item(Res.string.shopping_list_settings, onListSettings))
                        }
                    }
                    entries.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            menu = false
                            action()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun AddField(onAdd: (String) -> Unit, onScan: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(Res.string.shopping_add_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            if (text.isNotBlank()) {
                IconButton(onClick = submit) { Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.shopping_add)) }
            } else {
                IconButton(onClick = onScan) { Icon(PussletIcons.Barcode, contentDescription = stringResource(Res.string.shopping_scan)) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionChips(state: ShoppingUiState, actions: ShoppingActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AssistChip(onClick = actions.onStoreMode, label = { Text(stringResource(Res.string.shopping_store_mode)) })
        if (state.iAmShopping) {
            AssistChip(onClick = actions.onStopShopping, label = { Text(stringResource(Res.string.shopping_stop_shopping)) })
        } else {
            AssistChip(onClick = actions.onStartShopping, label = { Text(stringResource(Res.string.shopping_shop_now)) })
        }
    }
}

@Composable
private fun Staples(staples: List<StapleSuggestion>, actions: ShoppingActions) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(
            stringResource(Res.string.shopping_staples),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(staples, key = { it.key }) { s ->
                InputChip(
                    selected = false,
                    onClick = { actions.onAddStaple(s) },
                    label = {
                        Column(Modifier.padding(vertical = Spacing.xs)) {
                            Text(s.name)
                            Text(
                                stapleInterval(s),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.shopping_staple_add, s.name)) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.shopping_staple_dismiss),
                            modifier = Modifier.size(18.dp).clickable { actions.onDismissStaple(s) },
                        )
                    },
                )
            }
        }
    }
}

/** "ungefär var 4:e dag", "ungefär en gång i veckan". */
@Composable
internal fun stapleInterval(s: StapleSuggestion): String {
    val days = s.typicalInterval.inWholeHours.let { ((it + HALF_DAY_HOURS) / DAY_HOURS).toInt() }.coerceAtLeast(1)
    return when (days) {
        1 -> stringResource(Res.string.shopping_staple_daily)
        2 -> stringResource(Res.string.shopping_staple_every_other_day)
        in 6..8 -> stringResource(Res.string.shopping_staple_weekly)
        in 13..15 -> stringResource(Res.string.shopping_staple_biweekly)
        // Swedish ordinals: 21:a, 22:a but 11:e, 12:e, 3:e …
        else -> if (days % 10 in 1..2 && days % 100 !in 11..12) {
            stringResource(Res.string.shopping_staple_every_a, days)
        } else {
            stringResource(Res.string.shopping_staple_every_e, days)
        }
    }
}

@Composable
internal fun AisleHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xxs).semantics { heading() },
    )
}

@Composable
private fun ItemRow(item: ShoppingItem, state: ShoppingUiState, onCheck: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckRow(
            text = item.name,
            checked = item.checked,
            onCheckedChange = onCheck,
            supporting = itemDetails(item, state),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(Res.string.item_edit_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "2 st · Till Fredagstacos · 24,90 kr" */
@Composable
internal fun itemDetails(item: ShoppingItem, state: ShoppingUiState? = null): String? {
    val parts = buildList {
        item.amount?.let { add(formatQuantity(it)) }
        item.note.takeIf { it.isNotBlank() }?.let { add(it) }
        val recipes = item.sources.map { it.label }.filter { it.isNotBlank() }.distinct()
        if (recipes.isNotEmpty()) add(stringResource(Res.string.shopping_for, recipes.joinToString(", ")))
        item.price?.let { add(stringResource(Res.string.shopping_price, formatPrice(it))) }
        if (item.checked) item.checkedBy?.let { state?.people?.get(it)?.name }?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/** Plain text for "Dela listan", grouped by aisle in walking order, in the user's language. */
internal suspend fun shareText(state: ShoppingUiState): String = buildString {
    appendLine(getString(Res.string.shopping_share_title, state.list?.name.orEmpty()))
    val separator = getString(Res.string.decimal_separator)
    for (group in state.groups) {
        appendLine()
        appendLine(getString(Labels.aisle(group.aisle)))
        for (item in group.items) {
            val amount = item.amount?.let { q ->
                val number = se.pusslet.core.units.Units.formatNumber(q.value).replace(",", separator)
                listOfNotNull(number, q.unit?.let { getString(Labels.unit(it)) }).joinToString(" ")
            }
            appendLine("• " + listOfNotNull(amount, item.name).joinToString(" ") + if (item.note.isNotBlank()) " (${item.note})" else "")
        }
    }
}.trimEnd()

private const val DAY_HOURS = 24L
private const val HALF_DAY_HOURS = 12L

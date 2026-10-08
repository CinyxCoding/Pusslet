package se.pusslet.feature.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Store
import se.pusslet.core.shopping.ShoppingInput
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatQuantity
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

// region Pantry ("Hemma")

data class PantryState(val loading: Boolean = true, val items: List<PantryItem> = emptyList(), val usePantry: Boolean = false)

class PantryViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    val state: StateFlow<PantryState> = combine(shopping.pantry, family.settings) { items, settings ->
        PantryState(false, items.sortedBy { it.name.lowercase() }, settings.usePantry)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PantryState())

    /** "1 kg ris, pasta" -> two things at home; an amount is optional ("pasta" = we have enough). */
    fun add(text: String) {
        viewModelScope.launch {
            ShoppingInput.parse(text).forEach { p ->
                shopping.savePantry(PantryItem(ids.newId(), p.name, p.quantity?.value, p.quantity?.unit))
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { shopping.deletePantry(id) }
    }

    fun setUsePantry(on: Boolean) {
        viewModelScope.launch { family.saveSettings { it.copy(usePantry = on) } }
    }
}

@Composable
fun PantryRoute(onBack: () -> Unit, viewModel: PantryViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.pantry_title), onBack = onBack) }) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item {
                Text(stringResource(Res.string.pantry_body), style = MaterialTheme.typography.bodyMedium)
            }
            item { SwitchRow(stringResource(Res.string.pantry_use), state.usePantry, viewModel::setUsePantry) }
            item {
                val submit = {
                    if (text.isNotBlank()) {
                        viewModel.add(text)
                        text = ""
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(Res.string.pantry_add_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = submit) { Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.action_add)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.items.isEmpty()) item { EmptyState(stringResource(Res.string.pantry_empty)) }
            items(state.items, key = { it.id }) { p ->
                ListRow(
                    title = p.name,
                    supporting = p.quantity?.let { q -> formatQuantity(se.pusslet.core.units.Quantity(q, p.unit)) },
                    trailing = {
                        IconButton(onClick = { viewModel.remove(p.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.action_delete))
                        }
                    },
                )
            }
        }
    }
}

// endregion

// region Stores with their own aisle order

data class StoresState(val loading: Boolean = true, val stores: List<Store> = emptyList(), val defaultStoreId: String? = null)

class StoresViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    val state: StateFlow<StoresState> = combine(shopping.stores, family.settings) { stores, settings ->
        StoresState(false, stores.sortedBy { it.name.lowercase() }, settings.defaultStoreId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StoresState())

    fun create(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val store = Store(ids.newId(), name.trim())
            shopping.saveStore(store)
            onCreated(store.id)
        }
    }

    fun save(store: Store) {
        viewModelScope.launch { shopping.saveStore(store.copy(name = store.name.trim())) }
    }

    /** Moves an aisle one step in the walking order. */
    fun move(store: Store, aisle: Aisle, by: Int) {
        val order = store.aisleOrder.toMutableList()
        val i = order.indexOf(aisle)
        val j = i + by
        if (i < 0 || j !in order.indices) return
        order[i] = order[j]
        order[j] = aisle
        save(store.copy(aisleOrder = order))
    }

    fun resetOrder(store: Store) = save(store.copy(aisleOrder = Aisle.defaultOrder))

    fun setDefault(id: String?) {
        viewModelScope.launch { family.saveSettings { it.copy(defaultStoreId = id) } }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            shopping.deleteStore(id)
            family.saveSettings { if (it.defaultStoreId == id) it.copy(defaultStoreId = null) else it }
        }
    }
}

@Composable
fun StoresRoute(onBack: () -> Unit, viewModel: StoresViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val store = state.stores.firstOrNull { it.id == editing }
    if (store != null) {
        StoreEditor(store, isDefault = state.defaultStoreId == store.id, viewModel, onBack = { editing = null })
        return
    }
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.stores_title), onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.stores_new)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal)) {
            item {
                Text(
                    stringResource(Res.string.stores_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
            if (state.stores.isEmpty()) item { EmptyState(stringResource(Res.string.stores_empty)) }
            items(state.stores, key = { it.id }) { s ->
                ListRow(
                    title = s.name,
                    supporting = if (s.id == state.defaultStoreId) stringResource(Res.string.stores_default) else null,
                    modifier = Modifier.clickable { editing = s.id },
                )
                HorizontalDivider()
            }
        }
    }
    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(Res.string.stores_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.store_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    viewModel.create(name) { editing = it }
                }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StoreEditor(store: Store, isDefault: Boolean, viewModel: StoresViewModel, onBack: () -> Unit) {
    var name by remember(store.id) { mutableStateOf(store.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(store.id) { name = store.name }
    Scaffold(topBar = {
        PussletTopBar(store.name, onBack = {
            if (name.isNotBlank() && name != store.name) viewModel.save(store.copy(name = name))
            onBack()
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.store_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(stringResource(Res.string.stores_default), isDefault, { on ->
                        viewModel.setDefault(if (on) store.id else null)
                    })
                    Text(stringResource(Res.string.store_order), style = MaterialTheme.typography.titleMedium)
                }
            }
            itemsIndexed(store.aisleOrder, key = { _, a -> a.name }) { i, aisle ->
                val label = stringResource(Labels.aisle(aisle))
                Row(Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. $label", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.move(store, aisle, -1) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(Res.string.store_move_up, label))
                    }
                    IconButton(onClick = { viewModel.move(store, aisle, 1) }, enabled = i < store.aisleOrder.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(Res.string.store_move_down, label))
                    }
                }
            }
            item {
                Column {
                    TextButton(onClick = { viewModel.resetOrder(store) }) { Text(stringResource(Res.string.store_reset_order)) }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(Res.string.action_delete)) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.store_delete_confirm, store.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(store.id)
                    onBack()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

// endregion

private const val STOP_TIMEOUT_MS = 5_000L

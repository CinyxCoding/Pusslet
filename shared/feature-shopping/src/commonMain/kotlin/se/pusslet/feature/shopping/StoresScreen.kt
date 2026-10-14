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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.Store
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.ReorderButtons
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import se.pusslet.core.model.Role as FamilyRole

data class StoresUiState(
    val loading: Boolean = true,
    val stores: List<Store> = emptyList(),
    val defaultStoreId: String? = null,
    val canManage: Boolean = true,
)

class StoresViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    sessions: SessionProvider,
    private val ids: IdGenerator,
) : ViewModel() {
    val state: StateFlow<StoresUiState> = combine(shopping.stores, family.settings, sessions.session) { stores, settings, session ->
        StoresUiState(
            loading = false,
            stores = stores.sortedBy { it.name.lowercase() },
            defaultStoreId = settings.defaultStoreId,
            canManage = session.role == FamilyRole.Parent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StoresUiState())

    private val finished = MutableStateFlow(false)

    /** True once a save or delete has been written; the editor then closes (never before, or the write is cancelled). */
    val done: StateFlow<Boolean> = finished

    fun newStoreId(): String = ids.newId()

    fun save(store: Store, isDefault: Boolean) {
        viewModelScope.launch {
            shopping.saveStore(store.copy(name = store.name.trim()))
            val current = state.value.defaultStoreId
            when {
                isDefault && current != store.id -> family.saveSettings { it.copy(defaultStoreId = store.id) }
                !isDefault && current == store.id -> family.saveSettings { it.copy(defaultStoreId = null) }
            }
            finished.value = true
        }
    }

    fun delete(store: Store) {
        viewModelScope.launch {
            shopping.deleteStore(store.id)
            if (state.value.defaultStoreId == store.id) family.saveSettings { it.copy(defaultStoreId = null) }
            finished.value = true
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun StoresRoute(onBack: () -> Unit, onEdit: (String?) -> Unit, viewModel: StoresViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.stores_title), onBack = onBack) },
        floatingActionButton = {
            if (state.canManage) {
                // A sub page with its own round + ("Ny butik"), no text on the button (ADR-023).
                FloatingActionButton(onClick = { onEdit(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.stores_add))
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + Spacing.listBottomInset,
            ),
        ) {
            if (state.stores.isEmpty()) {
                item { EmptyState(message = stringResource(Res.string.stores_empty)) }
            }
            items(state.stores, key = { it.id }) { store ->
                val isDefault = store.id == state.defaultStoreId
                ListRow(
                    title = store.name,
                    supporting = if (isDefault) stringResource(Res.string.store_default) else null,
                    modifier = Modifier.clickable(enabled = state.canManage, role = Role.Button) { onEdit(store.id) },
                    trailing = if (isDefault) {
                        { Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Name, "standardbutik" and the walking order, reordered with up/down buttons (works with TalkBack, unlike drag). */
@Composable
fun StoreEditorRoute(storeId: String?, onDone: () -> Unit, viewModel: StoresViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onDone() }
    if (state.loading) {
        LoadingBox()
        return
    }
    val existing = storeId?.let { id -> state.stores.firstOrNull { it.id == id } }
    val id = rememberSaveable { storeId ?: viewModel.newStoreId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var isDefault by rememberSaveable { mutableStateOf(state.defaultStoreId == id || (existing == null && state.stores.isEmpty())) }
    val order = remember { mutableStateListOf<Aisle>().apply { addAll(fullOrder(existing?.aisleOrder ?: Aisle.defaultOrder)) } }
    var confirmDelete by remember { mutableStateOf(false) }
    val title = stringResource(if (existing == null) Res.string.stores_add else Res.string.store_edit)

    Scaffold(topBar = { PussletTopBar(title, onBack = onDone) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.store_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "default") {
                SwitchRow(
                    title = stringResource(Res.string.store_default),
                    supporting = stringResource(Res.string.store_default_body),
                    checked = isDefault,
                    onCheckedChange = { isDefault = it },
                )
            }
            item(key = "order-title") {
                Column(Modifier.padding(top = Spacing.md)) {
                    Text(stringResource(Res.string.store_order_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(Res.string.store_order_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(order, key = { _, a -> a.name }) { index, aisle ->
                AisleOrderRow(
                    position = index + 1,
                    aisle = aisle,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onMove = { delta -> order.add(index + delta, order.removeAt(index)) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "reset") {
                TextButton(onClick = {
                    order.clear()
                    order.addAll(Aisle.defaultOrder)
                }) { Text(stringResource(Res.string.store_reset_order)) }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (existing != null) {
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(Res.string.action_delete))
                        }
                    }
                    Button(
                        onClick = { viewModel.save(Store(id, name, order.toList()), isDefault) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(Res.string.action_save)) }
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.store_delete_confirm, existing.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(existing)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** Stores saved before an aisle existed still list every aisle (new ones at the end). */
private fun fullOrder(saved: List<Aisle>): List<Aisle> = saved.distinct() + Aisle.defaultOrder.filter { it !in saved }

@Composable
private fun AisleOrderRow(
    position: Int,
    aisle: Aisle,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Labels.aisle(aisle))
    Row(
        modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            position.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.xs),
        )
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ReorderButtons(
            name = label,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = { onMove(-1) },
            onMoveDown = { onMove(1) },
        )
    }
}

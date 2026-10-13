package se.pusslet.feature.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Role
import se.pusslet.core.shopping.ShoppingInput
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

data class PantryUiState(
    val loading: Boolean = true,
    val items: List<PantryItem> = emptyList(),
    val usePantry: Boolean = false,
    val canManage: Boolean = true,
)

/** "Hemma"-lagret: what the family already has, deducted when the list is made from the meal plan. */
class PantryViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    sessions: SessionProvider,
    private val ids: IdGenerator,
) : ViewModel() {
    val state: StateFlow<PantryUiState> = combine(shopping.pantry, family.settings, sessions.session) { items, settings, session ->
        PantryUiState(
            loading = false,
            items = items.sortedBy { it.name.lowercase() },
            usePantry = settings.usePantry,
            canManage = session.role == Role.Parent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PantryUiState())

    /** "1 kg mjöl, ris": an item already at home gets the new amount instead of a second line. */
    fun add(text: String) {
        viewModelScope.launch {
            val existing = shopping.pantry.first()
            ShoppingInput.parse(text).forEach { parsed ->
                val key = ShoppingNames.canonical(parsed.name)
                val match = existing.firstOrNull { ShoppingNames.canonical(it.name) == key }
                shopping.savePantry(
                    PantryItem(
                        id = match?.id ?: ids.newId(),
                        name = match?.name ?: parsed.name,
                        quantity = parsed.quantity?.value,
                        unit = parsed.quantity?.unit,
                    ),
                )
            }
        }
    }

    fun remove(item: PantryItem) {
        viewModelScope.launch { shopping.deletePantry(item.id) }
    }

    fun setUsePantry(enabled: Boolean) {
        viewModelScope.launch { family.saveSettings { it.copy(usePantry = enabled) } }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun PantryRoute(onBack: () -> Unit, viewModel: PantryViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.pantry_title), onBack = onBack) }) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        PantryScreen(state, padding, onAdd = viewModel::add, onRemove = viewModel::remove, onUsePantry = viewModel::setUsePantry)
    }
}

@Composable
fun PantryScreen(
    state: PantryUiState,
    padding: PaddingValues = PaddingValues(),
    onAdd: (String) -> Unit = {},
    onRemove: (PantryItem) -> Unit = {},
    onUsePantry: (Boolean) -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "intro") {
            Text(
                stringResource(Res.string.pantry_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "use") {
            SwitchRow(
                title = stringResource(Res.string.pantry_use),
                checked = state.usePantry,
                onCheckedChange = onUsePantry,
                enabled = state.canManage,
            )
        }
        item(key = "add") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Res.string.pantry_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = ::submit, enabled = text.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.action_add))
                    }
                },
            )
        }
        if (state.items.isEmpty()) {
            item(key = "empty") { EmptyState(message = stringResource(Res.string.pantry_empty)) }
        }
        items(state.items, key = { it.id }) { item ->
            ListRow(
                title = item.name,
                supporting = item.quantity?.let { Units.format(Quantity(it, item.unit)) } ?: stringResource(Res.string.pantry_enough),
                trailing = {
                    IconButton(onClick = { onRemove(item) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.pantry_remove, item.name))
                    }
                },
            )
        }
    }
}

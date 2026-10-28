package se.pusslet.feature.family.wishlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Child
import se.pusslet.core.model.Role
import se.pusslet.core.model.WishItem
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.WishRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.format.possessive
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.wish_empty
import se.pusslet.ui.resources.wish_hint
import se.pusslet.ui.resources.wish_link
import se.pusslet.ui.resources.wish_open_link
import se.pusslet.ui.resources.wish_remove
import se.pusslet.ui.resources.wish_taken
import se.pusslet.ui.resources.wish_taken_by
import se.pusslet.ui.resources.wish_title
import se.pusslet.ui.theme.Spacing

data class WishlistUiState(
    val loading: Boolean = true,
    val child: Child? = null,
    val wishes: List<WishItem> = emptyList(),
    /** Member id -> name, for "Köpt av morfar". */
    val names: Map<String, String> = emptyMap(),
    val canEdit: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WishlistViewModel(
    private val wishes: WishRepository,
    family: FamilyRepository,
    private val sessions: SessionProvider,
) : ViewModel() {
    private val childId = MutableStateFlow<String?>(null)

    val state: StateFlow<WishlistUiState> = combine(
        childId.filterNotNull().flatMapLatest { wishes.forChild(it) },
        family.children,
        family.people,
        childId,
        sessions.session,
    ) { list, children, people, id, session ->
        WishlistUiState(
            loading = false,
            child = children.firstOrNull { it.id == id },
            wishes = list,
            names = people.associate { it.id to it.name },
            canEdit = session.role == Role.Parent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WishlistUiState())

    fun load(id: String) {
        childId.value = id
    }

    fun add(text: String, link: String) {
        val id = childId.value ?: return
        viewModelScope.launch { wishes.add(id, text, link) }
    }

    fun setTaken(wish: WishItem, taken: Boolean) {
        viewModelScope.launch { wishes.setTaken(wish, taken) }
    }

    fun remove(wish: WishItem) {
        viewModelScope.launch { wishes.delete(wish.id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun WishlistRoute(childId: String, onBack: () -> Unit, viewModel: WishlistViewModel = koinViewModel()) {
    LaunchedEffect(childId) { viewModel.load(childId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    WishlistScreen(state, onBack, viewModel::add, viewModel::setTaken, viewModel::remove)
}

@Composable
fun WishlistScreen(
    state: WishlistUiState,
    onBack: () -> Unit,
    onAdd: (String, String) -> Unit,
    onTaken: (WishItem, Boolean) -> Unit,
    onRemove: (WishItem) -> Unit,
) {
    val name = state.child?.firstName.orEmpty()
    var text by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    val platform = LocalPlatformActions.current
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text, link)
            text = ""
            link = ""
        }
    }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.wish_title, possessive(name)), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.canEdit) {
                item(key = "add") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(Res.string.wish_hint, name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "link") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            placeholder = { Text(stringResource(Res.string.wish_link)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text(stringResource(Res.string.action_add)) }
                    }
                }
            }
            if (!state.loading && state.wishes.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(Res.string.wish_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.wishes, key = { it.id }) { wish ->
                val takenText = if (wish.taken) {
                    wish.takenBy?.let { state.names[it] }?.let { stringResource(Res.string.wish_taken_by, it) }
                        ?: stringResource(Res.string.wish_taken)
                } else {
                    null
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = wish.taken,
                        onCheckedChange = { onTaken(wish, it) },
                        modifier = Modifier.semantics { takenText?.let { stateDescription = it } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            wish.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (wish.taken) TextDecoration.LineThrough else null,
                        )
                        takenText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (wish.link.isNotBlank()) {
                        TextButton(onClick = { platform.openUrl(wish.link) }) { Text(stringResource(Res.string.wish_open_link)) }
                    }
                    if (state.canEdit) {
                        IconButton(onClick = { onRemove(wish) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.wish_remove, wish.text))
                        }
                    }
                }
            }
        }
    }
}

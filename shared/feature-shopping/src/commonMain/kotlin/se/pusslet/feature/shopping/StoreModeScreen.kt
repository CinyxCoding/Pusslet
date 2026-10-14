package se.pusslet.feature.shopping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.ShoppingItem
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Butiksläge: big text, aisles in the store's walking order, ticked items sink to the bottom, the screen stays on and
 * the family sees "Sara handlar nu" while it is open. Ticking off can be undone from the snackbar.
 */
@Composable
fun StoreModeRoute(onBack: () -> Unit, viewModel: ShoppingViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val platform = LocalPlatformActions.current
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }
    var celebrate by remember { mutableStateOf(false) }

    // Presence needs the active list, which arrives with the first state. It ends when this screen's view model
    // is cleared (leaving store mode), or by itself after an hour.
    val listId = state.list?.id
    LaunchedEffect(listId) {
        if (listId != null) viewModel.startShopping()
    }
    DisposableEffect(Unit) {
        platform.keepScreenOn(true)
        onDispose { platform.keepScreenOn(false) }
    }
    ShoppingMessages(viewModel, snackbar, onUnknownProduct = {}, onAllDone = { celebrate = true })

    Scaffold(
        topBar = { PussletTopBar(title = state.list?.name ?: stringResource(Res.string.shopping_store_mode), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        StoreModeScreen(
            state = state,
            padding = padding,
            celebrate = celebrate || (state.openCount == 0 && state.checked.isNotEmpty()),
            onToggle = { item, checked -> viewModel.setChecked(item, checked, inStore = true) },
            onEdit = { editing = it },
            onClearChecked = viewModel::clearChecked,
            onFinish = onBack,
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
}

@Composable
fun StoreModeScreen(
    state: ShoppingUiState,
    padding: PaddingValues = PaddingValues(),
    celebrate: Boolean = false,
    onToggle: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    onEdit: (ShoppingItem) -> Unit = {},
    onClearChecked: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    if (state.loading) {
        LoadingBox()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screenHorizontal,
            end = Spacing.screenHorizontal,
            top = padding.calculateTopPadding() + Spacing.sm,
            bottom = padding.calculateBottomPadding() + Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        item(key = "left") {
            Text(
                when {
                    state.isEmpty -> stringResource(Res.string.shopping_empty_title)
                    state.allBought -> stringResource(Res.string.shopping_nothing_left)
                    else -> stringResource(Res.string.shopping_left_count, state.openCount)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "done") {
            AnimatedVisibility(visible = celebrate && state.openCount == 0, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                AllDoneCard(onClearChecked = onClearChecked, onFinish = onFinish)
            }
        }
        state.groups.forEach { group ->
            item(key = "aisle-${group.aisle}") {
                Text(
                    stringResource(Labels.aisle(group.aisle)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.lg).semantics { heading() },
                )
            }
            items(group.items, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    onToggle = { onToggle(item, it) },
                    onEdit = { onEdit(item) },
                    large = true,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (state.checked.isNotEmpty()) {
            item(key = "cart") {
                Column(Modifier.padding(top = Spacing.xl)) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.shopping_in_cart_count, state.checked.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
                    )
                }
            }
            items(state.checked, key = { it.id }) { item ->
                ItemRow(
                    item = item,
                    onToggle = { onToggle(item, it) },
                    onEdit = { onEdit(item) },
                    large = true,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** "Allt i vagnen!" – a calm little celebration, then the choice to clear the list or just leave. */
@Composable
private fun AllDoneCard(onClearChecked: () -> Unit, onFinish: () -> Unit) {
    SectionCard(title = null, modifier = Modifier.padding(vertical = Spacing.md)) {
        Text(
            "🎉",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.store_mode_all_done_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.store_mode_all_done_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = onClearChecked) { Text(stringResource(Res.string.shopping_clear_checked)) }
            Button(onClick = onFinish) { Text(stringResource(Res.string.store_mode_finish)) }
        }
    }
}

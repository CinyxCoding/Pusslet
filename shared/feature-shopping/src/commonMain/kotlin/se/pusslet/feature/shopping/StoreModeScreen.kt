package se.pusslet.feature.shopping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.ShoppingItem
import se.pusslet.ui.components.HeroCard
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Butiksläge (UX plan, Handla 7): the store's name with "3 av 8 i vagnen", big text, aisles in the store's walking order
 * and "Klar" always in the top bar. Ticked items sink into "I vagnen", where a tap puts them back; only the first tick of
 * the visit offers "Ångra". The screen stays on, and the family sees "Sara handlar nu" while it is open. When the last
 * item is in the cart, "Allt i vagnen!" shows at the bottom wherever the list is scrolled.
 */
@Composable
fun StoreModeRoute(onBack: () -> Unit, viewModel: ShoppingViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val platform = LocalPlatformActions.current
    var editing by remember { mutableStateOf<ShoppingItem?>(null) }

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
    ShoppingMessages(viewModel, snackbar, onUnknownProduct = {})

    Scaffold(
        topBar = { StoreModeTopBar(state, onBack = onBack, onDone = onBack) },
        bottomBar = {
            AnimatedVisibility(
                visible = !state.loading && state.allBought,
                enter = fadeIn() + scaleIn(initialScale = CELEBRATE_SCALE),
                exit = fadeOut(),
            ) {
                AllDoneCard(
                    onClearAndFinish = {
                        viewModel.finishShopping(clear = true)
                        onBack()
                    },
                    onKeep = onBack,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        StoreModeScreen(
            state = state,
            padding = padding,
            onToggle = { item, checked -> viewModel.setChecked(item, checked, inStore = true) },
            onEdit = { editing = it },
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

/** "ICA Nära Lindvägen" over "3 av 8 i vagnen" and a progress bar; ← and "Klar" are always there. */
@Composable
private fun StoreModeTopBar(state: ShoppingUiState, onBack: () -> Unit, onDone: () -> Unit) {
    val title = state.store?.name ?: state.list?.name ?: stringResource(Res.string.shopping_store_mode)
    val total = state.openCount + state.checked.size
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!state.loading) {
                        Text(
                            if (total == 0) {
                                stringResource(Res.string.shopping_empty_title)
                            } else {
                                stringResource(Res.string.store_mode_progress, state.checked.size, total)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Read out after every tick, so TalkBack users hear how far they have come.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.navigate_back))
                }
            },
            actions = { TextButton(onClick = onDone) { Text(stringResource(Res.string.store_mode_finish)) } },
        )
        if (total > 0) {
            LinearProgressIndicator(progress = { state.checked.size.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun StoreModeScreen(
    state: ShoppingUiState,
    padding: PaddingValues = PaddingValues(),
    onToggle: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    onEdit: (ShoppingItem) -> Unit = {},
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
        state.groups.forEach { group ->
            item(key = "aisle-${group.aisle}") {
                Text(
                    stringResource(Labels.aisle(group.aisle)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.lg).semantics { heading() }.animateItem(),
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
                Column(Modifier.padding(top = Spacing.xl).animateItem()) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.shopping_in_cart_count, state.checked.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
                    )
                }
            }
            // A tap puts an item back on the list, in its aisle.
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

/**
 * "Allt i vagnen!" – a calm little celebration at the bottom, then "Klar – töm vagnen" (the list shows "Ångra") or
 * "Behåll avbockade" (leave with the ticked items still on the list).
 */
@Composable
private fun AllDoneCard(onClearAndFinish: () -> Unit, onKeep: () -> Unit) {
    HeroCard(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎉", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
            Text(
                stringResource(Res.string.store_mode_all_done_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Button(onClick = onClearAndFinish, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.store_mode_finish_clear))
        }
        OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.store_mode_keep_checked))
        }
    }
}

private const val CELEBRATE_SCALE = 0.9f

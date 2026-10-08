package se.pusslet.feature.shopping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.ShoppingItem
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.Labels
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Butiksläge: big rows in the store's walking order, checked items drop into "I vagnen", the screen stays on and the
 * family sees "Sara handlar nu" while it is open. Things others add meanwhile are announced.
 */
@Composable
fun StoreModeRoute(listId: String, onDone: () -> Unit, viewModel: ShoppingViewModel = koinViewModel()) {
    val platform = LocalPlatformActions.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(listId) { viewModel.selectList(listId) }
    LaunchedEffect(listId) { viewModel.keepShopping() }
    LaunchedEffect(listId) { viewModel.watchForAdditions() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { m -> showMessage(snackbar, m, viewModel::restore) } }
    DisposableEffect(Unit) {
        platform.keepScreenOn(true)
        onDispose {
            platform.keepScreenOn(false)
            viewModel.stopShopping()
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    StoreModeScreen(state, snackbar, onCheck = viewModel::setChecked, onDone = onDone)
}

@Composable
fun StoreModeScreen(
    state: ShoppingUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onCheck: (ShoppingItem, Boolean) -> Unit = { _, _ -> },
    onDone: () -> Unit = {},
) {
    Scaffold(
        topBar = { PussletTopBar(state.list?.name ?: stringResource(Res.string.shopping_store_mode), onBack = onDone) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val allDone = state.remaining == 0 && state.checked.isNotEmpty()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xxl),
        ) {
            item(key = "status") {
                Text(
                    listOfNotNull(
                        stringResource(Res.string.store_mode_left, state.remaining),
                        state.store?.let { stringResource(Res.string.store_mode_order, it.name) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            item(key = "done") { DoneCelebration(allDone, onDone) }
            state.groups.forEach { group ->
                item(key = "aisle-${group.aisle.name}") {
                    Text(
                        stringResource(Labels.aisle(group.aisle)),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
                    )
                }
                items(group.items, key = { it.id }) { item -> BigItemRow(item, state, onCheck) }
            }
            if (state.checked.isNotEmpty()) {
                item(key = "cart") {
                    Column(Modifier.padding(top = Spacing.xl)) {
                        HorizontalDivider()
                        Text(
                            stringResource(Res.string.shopping_in_cart, state.checked.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                }
                items(state.checked, key = { "c-${it.id}" }) { item -> BigItemRow(item, state, onCheck) }
            }
        }
    }
}

@Composable
private fun BigItemRow(item: ShoppingItem, state: ShoppingUiState, onCheck: (ShoppingItem, Boolean) -> Unit) {
    CheckRow(
        text = item.name,
        checked = item.checked,
        onCheckedChange = { onCheck(item, it) },
        supporting = itemDetails(item, state),
        modifier = Modifier.heightIn(min = Spacing.primaryTouchTarget + 8.dp),
        large = true,
    )
}

/** A small, calm "Klart!" – a check that pops in, not confetti. */
@Composable
private fun DoneCelebration(visible: Boolean, onDone: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy))) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Text(stringResource(Res.string.store_mode_done_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.store_mode_done_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onDone, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.shopping_stop_shopping))
            }
        }
    }
}

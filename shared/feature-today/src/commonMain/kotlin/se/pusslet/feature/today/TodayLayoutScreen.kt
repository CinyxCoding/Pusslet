package se.pusslet.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.today.TodayBlock
import se.pusslet.core.today.TodayLayoutItem
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.ReorderButtons
import se.pusslet.ui.components.SectionHeader
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** "Anpassa Idag", reached from the last row on Idag and from Inställningar › Utseende. */
@Composable
fun TodayLayoutRoute(onBack: () -> Unit, viewModel: TodayLayoutViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.today_customize), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        TodayLayoutScreen(
            state = state,
            padding = padding,
            onToggle = viewModel::setVisible,
            onMove = viewModel::move,
            onReset = {
                scope.launch {
                    val previous = viewModel.reset()
                    val undo = snackbar.showUndo(getString(Res.string.today_layout_reset_done), getString(Res.string.action_undo))
                    if (undo) viewModel.restore(previous)
                }
            },
        )
    }
}

/**
 * Switches for every block of the viewer's Idag. Morgonkollen rows keep their fixed order; the sections below can also be
 * moved with up/down buttons (and TalkBack actions). A switched-off section keeps its place. Changes apply at once.
 */
@Composable
fun TodayLayoutScreen(
    state: TodayLayoutUiState,
    padding: PaddingValues = PaddingValues(),
    onToggle: (TodayBlock, Boolean) -> Unit = { _, _ -> },
    onMove: (TodayBlock, Boolean) -> Unit = { _, _ -> },
    onReset: () -> Unit = {},
) {
    // After a move TalkBack says where the section ended up ("Idag händer, plats 2 av 4").
    var announcement by remember { mutableStateOf("") }
    val sections = state.layout.sections
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(Res.string.today_layout_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.layout.hero.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.today_layout_hero), Modifier.padding(top = Spacing.md))
            state.layout.hero.forEach { item ->
                SwitchRow(
                    title = stringResource(blockName(item.block)),
                    checked = item.visible,
                    onCheckedChange = { onToggle(item.block, it) },
                    supporting = if (item.block == TodayBlock.Weather && !state.hasWeatherPlace) {
                        stringResource(Res.string.today_layout_weather_place)
                    } else {
                        null
                    },
                )
            }
        }
        if (sections.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.today_layout_sections), Modifier.padding(top = Spacing.md))
            val scope = rememberCoroutineScope()
            sections.forEachIndexed { index, item ->
                SectionLayoutRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < sections.lastIndex,
                    onToggle = { onToggle(item.block, it) },
                    onMove = { up ->
                        onMove(item.block, up)
                        val position = if (up) index else index + 2
                        scope.launch {
                            announcement = getString(
                                Res.string.today_layout_position,
                                getString(blockName(item.block)),
                                position,
                                sections.size,
                            )
                        }
                    },
                )
            }
        }
        if (!state.isDefault) {
            TextButton(onClick = onReset, modifier = Modifier.padding(top = Spacing.md)) {
                Text(stringResource(Res.string.today_layout_reset))
            }
        }
        // Not visible, only read: a polite live region for the position after a move.
        Text(announcement, Modifier.alpha(0f).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun SectionLayoutRow(
    item: TodayLayoutItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMove: (up: Boolean) -> Unit,
) {
    val name = stringResource(blockName(item.block))
    val moveUp = stringResource(Res.string.today_layout_move_up)
    val moveDown = stringResource(Res.string.today_layout_move_down)
    // The switch row carries the move actions too, so TalkBack users can reorder without finding the small arrows.
    val actions = buildList {
        if (canMoveUp) {
            add(
                CustomAccessibilityAction(moveUp) {
                    onMove(true)
                    true
                },
            )
        }
        if (canMoveDown) {
            add(
                CustomAccessibilityAction(moveDown) {
                    onMove(false)
                    true
                },
            )
        }
    }
    val switch = @Composable { modifier: Modifier ->
        SwitchRow(
            title = name,
            checked = item.visible,
            onCheckedChange = onToggle,
            modifier = modifier.semantics { customActions = actions },
        )
    }
    val buttons = @Composable {
        ReorderButtons(
            name = name,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = { onMove(true) },
            onMoveDown = { onMove(false) },
        )
    }
    if (LocalDensity.current.fontScale >= LARGE_TEXT) {
        // With very large text the arrows get their own row, so nothing has to scroll sideways.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            switch(Modifier)
            buttons()
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            switch(Modifier.weight(1f))
            buttons()
        }
    }
}

internal fun blockName(block: TodayBlock): StringResource = when (block) {
    TodayBlock.Handoffs -> Res.string.today_block_handoffs
    TodayBlock.Packing -> Res.string.today_block_packing
    TodayBlock.Dinner -> Res.string.today_block_dinner
    TodayBlock.Weather -> Res.string.today_block_weather
    TodayBlock.Timeline -> Res.string.today_block_timeline
    TodayBlock.Tasks -> Res.string.today_block_tasks
    TodayBlock.Upcoming -> Res.string.today_block_upcoming
    TodayBlock.Shopping -> Res.string.today_block_shopping
}

private const val LARGE_TEXT = 1.5f

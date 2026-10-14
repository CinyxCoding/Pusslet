package se.pusslet.feature.today.helper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.today.TodayBlock
import se.pusslet.feature.today.TodayLayoutScreen
import se.pusslet.feature.today.TodayLayoutViewModel
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_undo
import se.pusslet.ui.resources.helper_full_view
import se.pusslet.ui.resources.helper_full_view_note
import se.pusslet.ui.resources.helper_home_sections
import se.pusslet.ui.resources.helper_settings
import se.pusslet.ui.resources.more_about_data
import se.pusslet.ui.resources.more_account
import se.pusslet.ui.resources.more_privacy
import se.pusslet.ui.resources.today_layout_reset_done
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.Spacing

/** Where "Inställningar och hjälp" leads. */
enum class HelperSettingsDestination { HomeSections, Account, Privacy, AboutData }

/**
 * "Inställningar och hjälp" in the helper view: what the home screen shows, the full app, and the account, privacy and
 * about pages. Nothing here changes the family's settings (a caregiver may not).
 */
@Composable
fun HelperSettingsScreen(onBack: () -> Unit, onOpen: (HelperSettingsDestination) -> Unit, onShowFullApp: () -> Unit) {
    val row = Modifier.heightIn(min = HelperDimens.row)
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.helper_settings), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
        ) {
            NavRow(stringResource(Res.string.helper_home_sections), { onOpen(HelperSettingsDestination.HomeSections) }, modifier = row)
            NavRow(
                stringResource(Res.string.helper_full_view),
                onShowFullApp,
                modifier = row,
                supporting = stringResource(Res.string.helper_full_view_note),
            )
            NavRow(stringResource(Res.string.more_account), { onOpen(HelperSettingsDestination.Account) }, modifier = row)
            NavRow(stringResource(Res.string.more_privacy), { onOpen(HelperSettingsDestination.Privacy) }, modifier = row)
            NavRow(stringResource(Res.string.more_about_data), { onOpen(HelperSettingsDestination.AboutData) }, modifier = row)
        }
    }
}

/**
 * "Visa på startsidan": the same switches and model as "Anpassa Idag" (Prefs.TodayLayout, ADR-027) with the caregiver's
 * blocks. The missions ("Lämning och hämtning") are the answer of the helper view and cannot be hidden, so they have no
 * switch here.
 */
@Composable
fun HelperHomeSectionsRoute(onBack: () -> Unit, viewModel: TodayLayoutViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.helper_home_sections), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox()
            return@Scaffold
        }
        val layout = state.layout.copy(hero = state.layout.hero.filter { it.block != TodayBlock.Handoffs })
        TodayLayoutScreen(
            state = state.copy(layout = layout),
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

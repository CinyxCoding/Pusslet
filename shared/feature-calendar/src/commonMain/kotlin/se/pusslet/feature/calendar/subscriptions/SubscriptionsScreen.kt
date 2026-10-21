package se.pusslet.feature.calendar.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.premium.FreeLimits
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.UnsavedChangesBackHandler
import se.pusslet.ui.components.showUndo
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * "Prenumerationer" (Inställningar, parents only): the list, and the add/edit screen on top of it in the same route, so that
 * "Ta bort" in the editor can offer "Ångra" in the list. Back closes the editor first (asking before changes are lost).
 */
@Composable
fun SubscriptionsRoute(onBack: () -> Unit, viewModel: SubscriptionsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }
    val editor = state.editor

    fun closeEditor() {
        if (editor?.hasChanges == true) confirmDiscard = true else viewModel.closeEditor()
    }
    UnsavedChangesBackHandler(enabled = editor != null && !confirmDiscard, onBack = ::closeEditor)

    val removal = state.removal
    LaunchedEffect(removal) {
        if (removal != null) {
            val undo = snackbar.showUndo(getString(Res.string.subs_removed, removal.name), getString(Res.string.action_undo))
            if (undo) viewModel.undoRemove(removal.token) else viewModel.removalShown(removal.token)
        }
    }

    if (editor != null) {
        SubscriptionEditorScreen(
            editor = editor,
            people = state.people,
            today = state.today,
            onBack = ::closeEditor,
            onUrl = viewModel::setUrl,
            onName = viewModel::setName,
            onTogglePerson = viewModel::togglePerson,
            onColor = viewModel::setColor,
            onTest = viewModel::testFetch,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        )
    } else {
        SubscriptionsScreen(
            state = state,
            snackbar = snackbar,
            onBack = onBack,
            onAdd = viewModel::add,
            onEdit = viewModel::edit,
            onEnabled = viewModel::setEnabled,
            onRefresh = viewModel::refreshNow,
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(Res.string.discard_changes_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.closeEditor()
                }) { Text(stringResource(Res.string.action_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** The subscriptions, each with its colour, people and last refresh, a switch to show it in the calendar and "Uppdatera nu". */
@Composable
fun SubscriptionsScreen(
    state: SubscriptionsUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.subs_title), onBack = onBack) },
        floatingActionButton = {
            if (state.canManage && !state.atFreeLimit) {
                // A sub page with its own round + (ADR-023), without text on the button.
                FloatingActionButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.subs_add))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            !state.canManage -> EmptyState(stringResource(Res.string.subs_parents_only), Modifier.padding(padding))
            state.rows.isEmpty() -> EmptyState(stringResource(Res.string.subs_empty), Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + Spacing.listBottomInset,
                ),
            ) {
                if (state.atFreeLimit) {
                    item(key = "free-limit") {
                        Text(
                            stringResource(Res.string.subs_free_limit, FreeLimits.SUBSCRIPTIONS),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.md),
                        )
                    }
                }
                items(state.rows, key = { it.subscription.id }) { row ->
                    SubscriptionListRow(row, state.today, onEdit, onEnabled)
                }
                item(key = "refresh") {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.refreshing,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg).heightIn(min = Spacing.primaryTouchTarget),
                    ) {
                        Text(stringResource(if (state.refreshing) Res.string.subs_testing else Res.string.subs_refresh_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionListRow(row: SubscriptionRow, today: LocalDate?, onEdit: (String) -> Unit, onEnabled: (String, Boolean) -> Unit) {
    val sub = row.subscription
    val switchLabel = stringResource(Res.string.subs_show_in_calendar, sub.name)
    val people = joinNames(row.people.map { it.name }).ifEmpty { null }
    ListRow(
        title = sub.name,
        supporting = listOfNotNull(people, statusText(row, today)).joinToString(" · "),
        modifier = Modifier.clickable(role = Role.Button) { onEdit(sub.id) },
        // The name is read from the title; the badge is the colour, with the initial so colour is not the only signal.
        leading = { ColorBadge(sub.color, sub.name.trim().take(1).uppercase(), "", Modifier.clearAndSetSemantics {}) },
        trailing = {
            Switch(
                checked = sub.enabled,
                onCheckedChange = { onEnabled(sub.id, it) },
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        },
    )
}

/** "Senast hämtad 07:12" (today), "Senast hämtad 19 okt 07:12", or the quiet "Kunde inte hämta – vi försöker igen". */
@Composable
private fun statusText(row: SubscriptionRow, today: LocalDate?): String {
    val fetched: LocalDateTime? = row.fetchedAt
    return when {
        row.status?.error != null -> stringResource(Res.string.subs_fetch_failed)
        fetched == null -> stringResource(Res.string.subs_never_fetched)
        fetched.date == today -> stringResource(Res.string.subs_last_fetched, formatTime(fetched.time))
        else -> stringResource(Res.string.subs_last_fetched, formatDateShort(fetched.date) + " " + formatTime(fetched.time))
    }
}

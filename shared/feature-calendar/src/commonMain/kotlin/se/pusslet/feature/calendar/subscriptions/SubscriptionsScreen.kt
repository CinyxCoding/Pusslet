package se.pusslet.feature.calendar.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.ui.components.ColorBadge
import se.pusslet.ui.components.ColorPicker
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PersonChips
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

@Composable
fun SubscriptionsRoute(onBack: () -> Unit, prefilledUrl: String? = null, viewModel: SubscriptionsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(prefilledUrl) { if (prefilledUrl != null) viewModel.startNew(prefilledUrl) }
    SubscriptionsScreen(
        state = state,
        onBack = onBack,
        onAdd = { viewModel.startNew() },
        onEdit = viewModel::startEdit,
        onEditDraft = viewModel::editDraft,
        onSave = viewModel::saveDraft,
        onCancel = viewModel::cancelDraft,
        onEnabled = viewModel::setEnabled,
        onRefresh = viewModel::refreshNow,
        onDelete = viewModel::delete,
    )
}

@Composable
fun SubscriptionsScreen(
    state: SubscriptionsUiState,
    onBack: () -> Unit = {},
    onAdd: () -> Unit = {},
    onEdit: (CalendarSubscription) -> Unit = {},
    onEditDraft: ((SubscriptionDraft) -> SubscriptionDraft) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEnabled: (CalendarSubscription, Boolean) -> Unit = { _, _ -> },
    onRefresh: (CalendarSubscription) -> Unit = {},
    onDelete: (CalendarSubscription) -> Unit = {},
) {
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.subs_title), onBack = onBack) },
        floatingActionButton = {
            if (state.canEdit) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(Res.string.subs_add)) },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xxl * 3),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { Text(stringResource(Res.string.subs_body), style = MaterialTheme.typography.bodyMedium) }
            if (state.rows.isEmpty()) item { EmptyState(stringResource(Res.string.subs_empty)) }
            items(state.rows, key = { it.subscription.id }) { row ->
                SubscriptionCard(row, state, onEdit, onEnabled, onRefresh, onDelete)
            }
        }
    }
    state.draft?.let { d -> DraftDialog(d, state, onEditDraft, onSave, onCancel) }
}

@Composable
private fun SubscriptionCard(
    row: SubscriptionRow,
    state: SubscriptionsUiState,
    onEdit: (CalendarSubscription) -> Unit,
    onEnabled: (CalendarSubscription, Boolean) -> Unit,
    onRefresh: (CalendarSubscription) -> Unit,
    onDelete: (CalendarSubscription) -> Unit,
) {
    val sub = row.subscription
    var confirmDelete by remember { mutableStateOf(false) }
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ColorBadge(sub.color, sub.name.take(1).uppercase(), sub.name, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium)
                val people = sub.personIds.mapNotNull { id -> state.people.firstOrNull { it.id == id }?.name }
                if (people.isNotEmpty()) {
                    Text(
                        people.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.canEdit) Switch(checked = sub.enabled, onCheckedChange = { onEnabled(sub, it) })
        }
        Text(statusText(row), style = MaterialTheme.typography.bodySmall, color = statusColor(row))
        if (state.canEdit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(Spacing.xs))
                } else {
                    IconButton(onClick = { onRefresh(sub) }) { Icon(Icons.Filled.Refresh, stringResource(Res.string.subs_refresh)) }
                }
                TextButton(onClick = { onEdit(sub) }) { Text(stringResource(Res.string.action_edit)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(Res.string.action_delete)) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.subs_delete_confirm, sub.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(sub)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** "24 händelser · hämtat 8 okt 10:42", or what went wrong in plain words. */
@Composable
private fun statusText(row: SubscriptionRow): String {
    val s = row.status
    val zone = koinInject<TimeZone>()
    val fetched = s.fetchedAt?.toLocalDateTime(zone)?.let { "${formatDateShort(it.date)} ${formatTime(it.time)}" }
    return when {
        fetched == null && s.lastError != null -> stringResource(Res.string.subs_failed)
        fetched == null -> stringResource(Res.string.subs_never)
        s.lastError != null -> stringResource(Res.string.subs_stale, fetched)
        else -> stringResource(Res.string.subs_status, s.eventCount, fetched)
    }
}

@Composable
private fun statusColor(row: SubscriptionRow) =
    if (row.status.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun DraftDialog(
    d: SubscriptionDraft,
    state: SubscriptionsUiState,
    onEdit: ((SubscriptionDraft) -> SubscriptionDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (d.id == null) Res.string.subs_add else Res.string.action_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = d.url,
                    onValueChange = { v -> onEdit { it.copy(url = v) } },
                    label = { Text(stringResource(Res.string.subs_url)) },
                    supportingText = {
                        Text(stringResource(if (d.invalidUrl) Res.string.subs_invalid_url else Res.string.subs_url_hint))
                    },
                    isError = d.invalidUrl,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { v -> onEdit { it.copy(name = v) } },
                    label = { Text(stringResource(Res.string.subs_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionCard(stringResource(Res.string.subs_for)) {
                    PersonChips(state.people, d.personIds, { id ->
                        onEdit { it.copy(personIds = if (id in it.personIds) it.personIds - id else it.personIds + id) }
                    })
                }
                Text(stringResource(Res.string.subs_color), style = MaterialTheme.typography.labelLarge)
                ColorPicker(d.color) { c -> onEdit { it.copy(color = c) } }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = d.url.isNotBlank()) { Text(stringResource(Res.string.action_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

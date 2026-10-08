package se.pusslet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.FamilyPerson
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.usecase.CalendarFeeds
import se.pusslet.data.usecase.FeedAvailability
import se.pusslet.data.usecase.FeedLink
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

data class FeedRow(val link: FeedLink, val url: String, val person: FamilyPerson?)

data class CalendarFeedState(
    val availability: FeedAvailability = FeedAvailability.NoBackend,
    val rows: List<FeedRow> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

class CalendarFeedViewModel(private val feeds: CalendarFeeds, family: FamilyRepository) : ViewModel() {
    private val log = Logger.withTag("CalendarFeeds")
    private val busy = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    val state: StateFlow<CalendarFeedState> = combine(feeds.availability, feeds.links, family.people, busy, failed) {
        a,
        links,
        people,
        b,
        f,
        ->
        CalendarFeedState(a, links.map { FeedRow(it, feeds.url(it), people.firstOrNull { p -> p.id == it.memberId }) }, b, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CalendarFeedState())

    fun create(mine: Boolean, onCreated: (String) -> Unit) = run { onCreated(feeds.url(feeds.create(mine))) }

    fun revoke(link: FeedLink) = run { feeds.revoke(link) }

    fun revokeAll() = run { feeds.revokeAll() }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            failed.value = false
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Calendar feed operation failed: ${e::class.simpleName}" }
                failed.value = true
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** "Familjekalendern i jobbets kalender": secret links work calendars can subscribe to. */
@Composable
fun CalendarFeedSection(viewModel: CalendarFeedViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val shareTitle = stringResource(Res.string.feed_share_subject)
    var confirmRevokeAll by remember { mutableStateOf(false) }
    SectionCard(stringResource(Res.string.feed_title)) {
        Text(stringResource(Res.string.feed_body), style = MaterialTheme.typography.bodyMedium)
        when (state.availability) {
            FeedAvailability.NoBackend -> Note(stringResource(Res.string.feed_no_backend))
            FeedAvailability.NeedsSignIn -> Note(stringResource(Res.string.feed_needs_sign_in))
            FeedAvailability.ParentsOnly -> Note(stringResource(Res.string.feed_parents_only))
            FeedAvailability.Ready -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { viewModel.create(mine = false) { platform.shareText(it, shareTitle) } },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
                    ) { Text(stringResource(Res.string.feed_create_family)) }
                    OutlinedButton(
                        onClick = { viewModel.create(mine = true) { platform.shareText(it, shareTitle) } },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget),
                    ) { Text(stringResource(Res.string.feed_create_mine)) }
                }
                state.rows.forEach { row ->
                    val label =
                        row.person?.let { stringResource(Res.string.feed_link_person, it.name) }
                            ?: stringResource(Res.string.feed_link_family)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        row.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Row {
                        TextButton(onClick = { platform.shareText(row.url, shareTitle) }) { Text(stringResource(Res.string.action_share)) }
                        TextButton(onClick = {
                            viewModel.revoke(row.link)
                        }, enabled = !state.busy) { Text(stringResource(Res.string.feed_revoke)) }
                    }
                }
                TextButton(onClick = { confirmRevokeAll = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.feed_revoke_all))
                }
            }
        }
        if (state.failed) {
            Text(
                stringResource(Res.string.feed_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmRevokeAll) {
        AlertDialog(
            onDismissRequest = { confirmRevokeAll = false },
            text = { Text(stringResource(Res.string.feed_revoke_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevokeAll = false
                    viewModel.revokeAll()
                }) { Text(stringResource(Res.string.feed_revoke)) }
            },
            dismissButton = { TextButton(onClick = { confirmRevokeAll = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

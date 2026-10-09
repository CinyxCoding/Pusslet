package se.pusslet.feature.family.otherhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.AppConfig
import se.pusslet.core.entitlements.Entitlements
import se.pusslet.core.entitlements.Feature
import se.pusslet.core.model.Child
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.usecase.OtherHomeSharing
import se.pusslet.data.usecase.SharedSpace
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SmallAction
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock
import androidx.compose.ui.semantics.Role as SemanticsRole

enum class OtherHomeError { Network, NotFound, FamilyInvite, AlreadyShared }

/** Answering an invite from the other home: which of our children each shared child is (null = new to us). */
data class JoinDraft(val invite: InviteInfo, val matches: Map<String, String?>, val shareExisting: Boolean = true)

data class OtherHomeUiState(
    val loading: Boolean = true,
    val available: Boolean = true,
    val needsAccount: Boolean = false,
    /** Starting a share is a premium candidate (MONETIZATION.md); joining the other home's is always free. */
    val canStart: Boolean = true,
    val spaces: List<SharedSpace> = emptyList(),
    /** Our children that are not shared yet. */
    val children: List<Child> = emptyList(),
    val selected: Set<String> = emptySet(),
    val shareExisting: Boolean = true,
    val shareDiets: Boolean = false,
    /** A code just made for the other home. */
    val invite: InviteInfo? = null,
    val code: String = "",
    val join: JoinDraft? = null,
    val busy: Boolean = false,
    val error: OtherHomeError? = null,
)

private data class Choices(
    val selected: Set<String>? = null,
    val shareExisting: Boolean = true,
    val shareDiets: Boolean = false,
    val invite: InviteInfo? = null,
    val code: String = "",
    val join: JoinDraft? = null,
    val busy: Boolean = false,
    val error: OtherHomeError? = null,
)

/** "Andra hemmet": share the children's schedule with the other home, or answer the other home's code (ADR-030). */
class OtherHomeViewModel(
    private val sharing: OtherHomeSharing,
    family: FamilyRepository,
    private val auth: AuthRepository,
    private val entitlements: Entitlements,
    private val clock: Clock,
) : ViewModel() {
    private val log = Logger.withTag("OtherHome")
    private val choices = MutableStateFlow(Choices())

    val state: StateFlow<OtherHomeUiState> = combine(sharing.spaces, family.children, family.custody, auth.currentUser, choices) {
        spaces,
        children,
        custody,
        user,
        c,
        ->
        val shared = spaces.flatMap { s -> s.children.map { it.id } }.toSet()
        val own = children.filter { it.id !in shared }
        // Children with a custody schedule are the likely ones.
        val suggested = custody.flatMap { it.childIds }.filter { id -> own.any { it.id == id } }.toSet()
        OtherHomeUiState(
            loading = false,
            available = sharing.available,
            needsAccount = sharing.available && user == null,
            canStart = entitlements.has(Feature.SharedCustody),
            spaces = spaces,
            children = own,
            selected = (c.selected ?: suggested).filter { id -> own.any { it.id == id } }.toSet(),
            shareExisting = c.shareExisting,
            shareDiets = c.shareDiets,
            invite = c.invite,
            code = c.code,
            join = c.join,
            busy = c.busy,
            error = c.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OtherHomeUiState())

    fun toggleChild(id: String) = choices.update { c ->
        val current = c.selected ?: state.value.selected
        c.copy(selected = if (id in current) current - id else current + id)
    }

    fun setShareExisting(on: Boolean) = choices.update { it.copy(shareExisting = on) }

    fun setShareDiets(on: Boolean) = choices.update { it.copy(shareDiets = on) }

    fun start() {
        val s = state.value
        if (s.selected.isEmpty() || !s.canStart) return
        run {
            sharing.start(s.selected.toList(), s.shareExisting, s.shareDiets).also { invite ->
                choices.update { it.copy(invite = invite, selected = null) }
            }
        }
    }

    fun newCode(spaceId: String) = run { sharing.newInvite(spaceId).also { invite -> choices.update { it.copy(invite = invite) } } }

    fun dismissInvite() = choices.update { it.copy(invite = null) }

    fun setCode(code: String) = choices.update { it.copy(code = code, join = null, error = null) }

    fun lookup() {
        val code = InviteCodes.normalize(choices.value.code.substringAfterLast('/').substringBefore('?'))
        if (!InviteCodes.isValid(code)) {
            choices.update { it.copy(error = OtherHomeError.NotFound) }
            return
        }
        run {
            val invite = sharing.lookup(code)?.takeIf { it.expiresAt > clock.now() }
            val s = state.value
            val error = when {
                invite == null -> OtherHomeError.NotFound
                !invite.shared -> OtherHomeError.FamilyInvite
                s.spaces.any { it.id == invite.familyId } -> OtherHomeError.AlreadyShared
                else -> null
            }
            val draft = invite?.takeIf { error == null }?.let { inv ->
                // Our child with the same first name is most likely the same child.
                val matches = inv.children.associate { ref ->
                    ref.id to
                        s.children.firstOrNull { it.firstName.equals(ref.name, ignoreCase = true) }?.id
                }
                JoinDraft(inv, matches)
            }
            choices.update { it.copy(join = draft, error = error) }
        }
    }

    fun setMatch(sharedId: String, ownId: String?) = choices.update { c ->
        c.copy(join = c.join?.let { j -> j.copy(matches = j.matches + (sharedId to ownId)) })
    }

    fun setJoinShareExisting(on: Boolean) = choices.update { c -> c.copy(join = c.join?.copy(shareExisting = on)) }

    fun join() {
        val draft = choices.value.join ?: return
        run {
            sharing.join(draft.invite, draft.matches, draft.shareExisting)
            choices.update { it.copy(join = null, code = "") }
        }
    }

    fun leave(spaceId: String) = run { sharing.leave(spaceId) }

    /** Runs an online operation with a busy flag; failures show as "check your connection". */
    private fun run(block: suspend () -> Unit) {
        choices.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                choices.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Sharing with the other home failed: ${e::class.simpleName}" }
                choices.update { it.copy(busy = false, error = OtherHomeError.Network) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun OtherHomeRoute(onBack: () -> Unit, onSignIn: () -> Unit, viewModel: OtherHomeViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf<SharedSpace?>(null) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.other_home_title), onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "intro") { Text(stringResource(Res.string.other_home_intro), style = MaterialTheme.typography.bodyLarge) }
            if (!state.available) {
                item(key = "unavailable") { Banner(stringResource(Res.string.other_home_unavailable), kind = BannerKind.Warning) }
            } else if (state.needsAccount) {
                item(key = "account") {
                    Banner(stringResource(Res.string.other_home_needs_account), kind = BannerKind.Warning, actions = {
                        TextButton(onClick = onSignIn) { Text(stringResource(Res.string.account_sign_in)) }
                    })
                }
            }
            state.error?.let { error -> item(key = "error") { ErrorBanner(error) } }
            items(state.spaces, key = { it.id }) { space -> SpaceCard(space, state.busy, viewModel, onLeave = { leaving = space }) }
            state.invite?.let { invite -> item(key = "invite") { InviteCard(invite, viewModel::dismissInvite) } }
            val ready = state.available && !state.needsAccount
            if (state.invite == null && state.children.isNotEmpty() && state.join == null) {
                item(key = "start") { StartCard(state, ready, viewModel) }
            }
            item(key = "join") { JoinCard(state, ready, viewModel) }
        }
    }
    leaving?.let { space ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            text = { Text(stringResource(Res.string.other_home_stop_confirm, joinNames(space.children.map { it.firstName }))) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    viewModel.leave(space.id)
                }) { Text(stringResource(Res.string.other_home_stop)) }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ErrorBanner(error: OtherHomeError) {
    val text = when (error) {
        OtherHomeError.Network -> Res.string.join_needs_network
        OtherHomeError.NotFound -> Res.string.join_invalid
        OtherHomeError.FamilyInvite -> Res.string.other_home_family_invite
        OtherHomeError.AlreadyShared -> Res.string.other_home_already
    }
    Banner(stringResource(text), kind = BannerKind.Problem)
}

@Composable
private fun SpaceCard(space: SharedSpace, busy: Boolean, viewModel: OtherHomeViewModel, onLeave: () -> Unit) {
    SectionCard(joinNames(space.children.map { it.firstName })) {
        val status = if (space.otherAdults.isEmpty()) {
            stringResource(Res.string.other_home_waiting)
        } else {
            stringResource(Res.string.other_home_shared_with, joinNames(space.otherAdults.map { it.displayName }))
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (space.shareDiets) Res.string.other_home_diets_shared else Res.string.other_home_diets_private),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (space.otherAdults.isEmpty()) {
                OutlinedButton(onClick = {
                    viewModel.newCode(space.id)
                }, enabled = !busy) { Text(stringResource(Res.string.other_home_new_code)) }
            }
            TextButton(onClick = onLeave, enabled = !busy) { Text(stringResource(Res.string.other_home_stop)) }
        }
    }
}

@Composable
private fun InviteCard(invite: InviteInfo, onDone: () -> Unit) {
    val platform = LocalPlatformActions.current
    SectionCard(stringResource(Res.string.invite_code_label), action = { SmallAction(stringResource(Res.string.action_done), onDone) }) {
        Text(InviteCodes.format(invite.code), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(
                Res.string.invite_valid_until,
                formatDateLong(invite.expiresAt.toLocalDateTime(TimeZone.currentSystemDefault()).date),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(Res.string.other_home_invite_ready), style = MaterialTheme.typography.bodyMedium)
        val text = stringResource(
            Res.string.other_home_share_text,
            invite.fromName,
            joinNames(invite.children.map { it.name }),
            AppConfig.APP_NAME,
            InviteCodes.format(invite.code),
        )
        OutlinedButton(onClick = {
            platform.shareText(text)
        }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
            Text(stringResource(Res.string.action_share))
        }
    }
}

@Composable
private fun StartCard(state: OtherHomeUiState, ready: Boolean, viewModel: OtherHomeViewModel) {
    SectionCard(stringResource(Res.string.other_home_pick_children)) {
        state.children.forEach { child ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)
                    .toggleable(value = child.id in state.selected, role = SemanticsRole.Checkbox) { viewModel.toggleChild(child.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = child.id in state.selected, onCheckedChange = null)
                Text(child.firstName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        SwitchRow(
            stringResource(Res.string.other_home_share_existing),
            state.shareExisting,
            viewModel::setShareExisting,
            supporting = stringResource(Res.string.other_home_share_existing_body),
        )
        SwitchRow(
            stringResource(Res.string.other_home_share_diets),
            state.shareDiets,
            viewModel::setShareDiets,
            supporting = stringResource(Res.string.other_home_share_diets_body),
        )
        if (!state.canStart) Banner(stringResource(Res.string.other_home_premium), kind = BannerKind.Info)
        Button(
            onClick = viewModel::start,
            enabled = ready && state.canStart && !state.busy && state.selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        ) { Text(stringResource(Res.string.other_home_start)) }
    }
}

@Composable
private fun JoinCard(state: OtherHomeUiState, ready: Boolean, viewModel: OtherHomeViewModel) {
    SectionCard(stringResource(Res.string.other_home_join_title)) {
        val draft = state.join
        if (draft == null) {
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::setCode,
                label = { Text(stringResource(Res.string.join_code_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            OutlinedButton(onClick = viewModel::lookup, enabled = ready && !state.busy && state.code.isNotBlank()) {
                Text(stringResource(Res.string.join_find))
            }
            return@SectionCard
        }
        Text(
            stringResource(Res.string.other_home_join_intro, draft.invite.fromName, joinNames(draft.invite.children.map { it.name })),
            style = MaterialTheme.typography.titleMedium,
        )
        draft.invite.children.forEach { ref ->
            Text(stringResource(Res.string.other_home_match_title, ref.name), style = MaterialTheme.typography.labelLarge)
            val options = state.children.map { it.id to stringResource(Res.string.other_home_match_same, it.firstName) } +
                (null to stringResource(Res.string.other_home_match_new))
            options.forEach { (id, label) ->
                val selected = draft.matches[ref.id] == id
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)
                        .selectable(selected = selected, role = SemanticsRole.RadioButton) { viewModel.setMatch(ref.id, id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(label, modifier = Modifier.padding(start = Spacing.sm))
                }
            }
        }
        SwitchRow(stringResource(Res.string.other_home_join_existing), draft.shareExisting, viewModel::setJoinShareExisting)
        Text(
            stringResource(Res.string.other_home_join_custody),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = { viewModel.setCode("") }) { Text(stringResource(Res.string.action_cancel)) }
            Button(onClick = viewModel::join, enabled = ready && !state.busy) { Text(stringResource(Res.string.join_button)) }
        }
    }
}

package se.pusslet.feature.family.invite

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.AppConfig
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.BackendConfig
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import androidx.compose.ui.semantics.Role as SemanticsRole

data class InviteUiState(
    val role: Role = Role.Parent,
    val claim: Member? = null,
    val invite: InviteInfo? = null,
    val familyName: String = "",
    val busy: Boolean = false,
    val needsAccount: Boolean = false,
    val failed: Boolean = false,
)

class InviteViewModel(
    private val family: FamilyRepository,
    private val auth: AuthRepository,
    private val config: BackendConfig,
) : ViewModel() {
    private val log = Logger.withTag("Invite")
    private val _state = MutableStateFlow(InviteUiState())
    val state: StateFlow<InviteUiState> = _state.asStateFlow()

    fun load(claimMemberId: String?) {
        viewModelScope.launch {
            val claim = claimMemberId?.let { id -> family.members.first().firstOrNull { it.id == id } }
            _state.update {
                it.copy(
                    claim = claim,
                    role = claim?.role ?: it.role,
                    familyName = family.family.first()?.name.orEmpty(),
                    needsAccount = !auth.available || auth.currentUser.value == null,
                )
            }
        }
    }

    fun setRole(role: Role) = _state.update { it.copy(role = role) }

    fun create() {
        _state.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            try {
                val invite = family.createInvite(_state.value.role, _state.value.claim?.id)
                _state.update { it.copy(invite = invite, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Invite failed: ${e::class.simpleName}" }
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    fun link(code: String): String = "https://${config.linkHost}/join/$code"
}

@Composable
fun InviteRoute(claimMemberId: String?, onBack: () -> Unit, onSignIn: () -> Unit, viewModel: InviteViewModel = koinViewModel()) {
    androidx.compose.runtime.LaunchedEffect(claimMemberId) { viewModel.load(claimMemberId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.invite_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.invite_body), style = MaterialTheme.typography.bodyLarge)
            if (state.needsAccount) {
                Banner(stringResource(Res.string.invite_needs_account), kind = BannerKind.Warning, actions = {
                    androidx.compose.material3.TextButton(onClick = onSignIn) { Text(stringResource(Res.string.account_sign_in)) }
                })
            }
            state.claim?.let { Text(stringResource(Res.string.invite_claim, it.displayName), style = MaterialTheme.typography.titleMedium) }
            val invite = state.invite
            if (invite == null) {
                listOf(
                    Role.Parent to Res.string.invite_role_parent,
                    Role.Caregiver to Res.string.invite_role_caregiver,
                ).forEach { (role, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(
                            min = 56.dp,
                        ).selectable(selected = state.role == role, role = SemanticsRole.RadioButton) {
                            viewModel.setRole(role)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.role == role, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
                if (state.failed) Banner(stringResource(Res.string.join_needs_network), kind = BannerKind.Problem)
                Button(
                    onClick = viewModel::create,
                    enabled = !state.busy && !state.needsAccount,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.invite_create)) }
            } else {
                val link = viewModel.link(invite.code)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // QR codes need a light quiet zone regardless of theme.
                    Box(Modifier.background(Color.White).padding(Spacing.lg)) {
                        Image(
                            painter = rememberQrCodePainter(link),
                            contentDescription = stringResource(Res.string.invite_qr_description),
                            modifier = Modifier.size(220.dp),
                        )
                    }
                }
                Text(stringResource(Res.string.invite_code_label), style = MaterialTheme.typography.labelLarge)
                Text(InviteCodes.format(invite.code), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Text(
                    stringResource(
                        Res.string.invite_valid_until,
                        formatDateLong(invite.expiresAt.toLocalDateTime(TimeZone.currentSystemDefault()).date),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val shareText =
                    stringResource(
                        Res.string.invite_share_text,
                        state.familyName,
                        AppConfig.APP_NAME,
                        link,
                        InviteCodes.format(invite.code),
                    )
                OutlinedButton(onClick = {
                    platform.shareText(shareText)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
                    Text(stringResource(Res.string.action_share))
                }
            }
        }
    }
}

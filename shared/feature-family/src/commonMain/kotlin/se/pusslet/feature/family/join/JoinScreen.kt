package se.pusslet.feature.family.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.InviteCodes
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.InviteInfo
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

enum class JoinError { NotFound, Network, Unavailable }

data class JoinUiState(
    val code: String = "",
    val invite: InviteInfo? = null,
    val myName: String = "",
    val color: MemberColor = MemberColor.Teal,
    val busy: Boolean = false,
    val error: JoinError? = null,
)

class JoinViewModel(
    private val family: FamilyRepository,
    private val remote: FamilyRemote,
    private val prefs: PreferencesRepository,
    private val clock: Clock,
) : ViewModel() {
    private val log = Logger.withTag("Join")
    private val _state = MutableStateFlow(JoinUiState(error = if (remote.available) null else JoinError.Unavailable))
    val state: StateFlow<JoinUiState> = _state.asStateFlow()

    fun setCode(code: String) = _state.update {
        it.copy(code = code, invite = null, error = if (remote.available) null else JoinError.Unavailable)
    }

    fun setName(name: String) = _state.update { it.copy(myName = name) }

    /** Accepts a raw code, "K7QD-M2XP-9R", or a full invite link from a QR code. */
    fun lookup(input: String = _state.value.code) {
        val code = InviteCodes.normalize(input.substringAfterLast('/').substringBefore('?'))
        if (!InviteCodes.isValid(code)) {
            _state.update { it.copy(error = JoinError.NotFound) }
            return
        }
        _state.update { it.copy(code = InviteCodes.format(code), busy = true, error = null) }
        viewModelScope.launch {
            try {
                val invite = family.lookupInvite(code)?.takeIf { it.expiresAt > clock.now() }
                _state.update { it.copy(invite = invite, busy = false, error = if (invite == null) JoinError.NotFound else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Invite lookup failed: ${e::class.simpleName}" }
                _state.update { it.copy(busy = false, error = JoinError.Network) }
            }
        }
    }

    fun join(onJoined: () -> Unit) {
        val s = _state.value
        val invite = s.invite ?: return
        if (s.myName.isBlank()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                family.join(invite, s.myName, s.color)
                prefs.set(Prefs.OnboardingDone, true)
                onJoined()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Join failed: ${e::class.simpleName}" }
                _state.update { it.copy(busy = false, error = JoinError.Network) }
            }
        }
    }
}

@Composable
fun JoinRoute(
    initialCode: String?,
    onBack: () -> Unit,
    onJoined: () -> Unit,
    viewModel: JoinViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    LaunchedEffect(initialCode) { if (!initialCode.isNullOrBlank()) viewModel.lookup(initialCode) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.join_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.join_body), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::setCode,
                label = { Text(stringResource(Res.string.join_code_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = {
                    platform.scanCode { it?.let(viewModel::lookup) }
                }) { Text(stringResource(Res.string.join_scan)) }
                Button(onClick = {
                    viewModel.lookup()
                }, enabled = !state.busy && state.code.isNotBlank()) { Text(stringResource(Res.string.join_find)) }
            }
            when (state.error) {
                JoinError.NotFound -> Banner(stringResource(Res.string.join_invalid), kind = BannerKind.Problem)
                JoinError.Network -> Banner(stringResource(Res.string.join_needs_network), kind = BannerKind.Problem)
                JoinError.Unavailable -> Banner(stringResource(Res.string.join_unavailable), kind = BannerKind.Warning)
                null -> Unit
            }
            val invite = state.invite
            if (invite != null) {
                Text(stringResource(Res.string.join_found, invite.familyName), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(if (invite.role == Role.Parent) Res.string.join_as_parent else Res.string.join_as_caregiver),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = state.myName,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(Res.string.onboarding_your_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Button(
                    onClick = { viewModel.join(onJoined) },
                    enabled = !state.busy && state.myName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.join_button)) }
            }
        }
    }
}

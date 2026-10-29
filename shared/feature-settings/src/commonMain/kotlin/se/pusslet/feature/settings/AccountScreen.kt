package se.pusslet.feature.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.data.remote.AuthError
import se.pusslet.data.remote.AuthException
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.sync.SyncState
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Instant

data class AccountUiState(
    val available: Boolean = false,
    val user: AuthUser? = null,
    val sync: SyncStatus = SyncStatus(),
    val busy: Boolean = false,
    val error: AuthError? = null,
    /** Someone else in the family has an account and can invite this person back after leaving the phone empty. */
    val othersHaveAccounts: Boolean = true,
)

class AccountViewModel(private val auth: AuthRepository, engine: SyncEngine, private val privacy: DataPrivacy) : ViewModel() {
    private val log = Logger.withTag("Account")
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<AuthError?>(null)

    val state: StateFlow<AccountUiState> =
        combine(auth.currentUser, engine.status, busy, error, privacy.othersHaveAccounts) { u, s, b, e, others ->
            AccountUiState(auth.available, u, s, b, e, others)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState(auth.available))

    private fun run(block: suspend () -> Unit) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: AuthException) {
                error.value = e.error
            } finally {
                busy.value = false
            }
        }
    }

    fun signIn(email: String, password: String) = run { auth.signInWithEmail(email, password) }

    fun create(email: String, password: String) = run { auth.createAccount(email, password) }

    fun google(idToken: String) = run { auth.signInWithGoogle(idToken) }

    /** "Logga ut" (ADR-059): with [forget] the family is also removed from this phone; the others keep everything. */
    fun signOut(forget: Boolean) = run {
        try {
            privacy.signOut(forget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Sign-out did not finish (${e::class.simpleName})" }
            error.value = AuthError.Unknown
        }
    }
}

@Composable
fun AccountRoute(onBack: () -> Unit, viewModel: AccountViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var askSignOut by remember { mutableStateOf(false) }
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.account_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!state.available) {
                Banner(stringResource(Res.string.account_local_only), kind = BannerKind.Info)
                return@Column
            }
            val user = state.user
            if (user == null || user.isAnonymous) {
                Text(
                    if (user?.isAnonymous ==
                        true
                    ) {
                        stringResource(Res.string.account_anonymous)
                    } else {
                        stringResource(Res.string.account_signed_out)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    email,
                    { email = it },
                    label = { Text(stringResource(Res.string.account_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(stringResource(Res.string.account_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.error?.let { Banner(stringResource(errorText(it)), kind = BannerKind.Problem) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = { viewModel.signIn(email, password) },
                        enabled =
                            !state.busy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = Spacing.primaryTouchTarget),
                    ) {
                        Text(stringResource(Res.string.account_sign_in))
                    }
                    OutlinedButton(
                        onClick = { viewModel.create(email, password) },
                        enabled =
                            !state.busy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = Spacing.primaryTouchTarget),
                    ) {
                        Text(stringResource(Res.string.account_create))
                    }
                }
                if (platform.googleSignInAvailable) {
                    OutlinedButton(onClick = {
                        platform.googleSignIn { token ->
                            token?.let(viewModel::google)
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
                        Text(stringResource(Res.string.account_google))
                    }
                }
            } else {
                Text(
                    stringResource(Res.string.account_signed_in_as, user.email ?: user.displayName ?: user.uid.take(6)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { askSignOut = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.account_sign_out))
                }
            }
            SyncCard(state.sync)
        }
    }
    if (askSignOut) {
        SignOutDialog(
            othersHaveAccounts = state.othersHaveAccounts,
            onDismiss = { askSignOut = false },
            onSignOut = { forget ->
                askSignOut = false
                viewModel.signOut(forget)
            },
        )
    }
}

/** "Logga ut": keep the family on this phone or remove it, each choice said in plain words (ADR-059). */
@Composable
private fun SignOutDialog(othersHaveAccounts: Boolean, onDismiss: () -> Unit, onSignOut: (forget: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_sign_out)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.account_sign_out_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onSignOut(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.account_sign_out_keep))
                }
                Text(stringResource(Res.string.account_sign_out_keep_body), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onSignOut(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.account_sign_out_forget))
                }
                Text(
                    stringResource(
                        if (othersHaveAccounts) Res.string.account_sign_out_forget_body else Res.string.account_sign_out_forget_alone,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
fun SyncCard(sync: SyncStatus) {
    SectionCard(stringResource(Res.string.sync_title)) {
        val text = when (sync.state) {
            SyncState.Disabled -> stringResource(Res.string.sync_disabled)
            SyncState.Connecting -> stringResource(Res.string.sync_connecting)
            SyncState.Offline -> stringResource(Res.string.sync_offline)
            SyncState.Synced, SyncState.Pushing, SyncState.Error -> sync.lastSyncedAt?.let {
                stringResource(
                    Res.string.sync_synced,
                    formatTime(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).time),
                )
            } ?: stringResource(Res.string.sync_connecting)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (sync.pendingWrites >
            0
        ) {
            Text(stringResource(Res.string.sync_pending, sync.pendingWrites), style = MaterialTheme.typography.bodyMedium)
        }
        if (sync.rejectedWrites >
            0
        ) {
            Text(
                stringResource(Res.string.sync_rejected, sync.rejectedWrites),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun errorText(e: AuthError): StringResource = when (e) {
    AuthError.InvalidCredentials -> Res.string.account_error_credentials
    AuthError.EmailInUse -> Res.string.account_error_email_in_use
    AuthError.WeakPassword -> Res.string.account_error_weak_password
    AuthError.Network, AuthError.Unavailable, AuthError.Unknown -> Res.string.account_error_network
}

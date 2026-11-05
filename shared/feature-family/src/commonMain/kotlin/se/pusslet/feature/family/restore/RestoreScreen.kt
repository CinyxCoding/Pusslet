package se.pusslet.feature.family.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.account.PasswordResetState
import se.pusslet.core.model.Role
import se.pusslet.data.remote.AccountFamily
import se.pusslet.data.remote.AuthError
import se.pusslet.data.remote.AuthException
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.usecase.PasswordReset
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.ForgotPassword
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.platform.GoogleSignInResult
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** Where "Jag har redan ett konto" is (ADR-063). */
enum class RestoreStep {
    /** Not signed in, or only anonymously (as the demo build is with the emulator): the sign-in form. */
    SignIn,

    /** Signed in: looking for the account's families on the server. */
    Searching,

    /** The families this account belongs to; one tap brings one back. */
    Found,

    /** The account belongs to no family that is left. */
    NoneFound,

    /** No answer from the server; "Försök igen" looks again. */
    Offline,
}

data class RestoreUiState(
    val step: RestoreStep = RestoreStep.SignIn,
    val families: List<AccountFamily> = emptyList(),
    /** The signed-in account as the person knows it: the e-mail address, or the name from Google. */
    val account: String? = null,
    val busy: Boolean = false,
    val error: AuthError? = null,
)

/**
 * Brings a family back to a new phone (ADR-063): sign in with the account used before, see which families it belongs to,
 * and choose one. The session then points at it, onboarding is done and the sync engine fetches everything. The guide
 * through the app is not opened: whoever comes back has used the app before.
 */
class RestoreViewModel(
    private val auth: AuthRepository,
    private val remote: FamilyRemote,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
) : ViewModel() {
    private val log = Logger.withTag("Restore")
    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()
    private val passwordReset = PasswordReset(auth)
    val passwordResetState: StateFlow<PasswordResetState> = passwordReset.state

    /** "Glömt lösenordet?" (ADR-064): on a new phone this is the only way back to the account. */
    fun sendPasswordReset(email: String) {
        viewModelScope.launch { passwordReset.send(email) }
    }

    /**
     * Each time the screen opens. An account that is still signed in, for example after the family was removed from this
     * phone, is looked up right away; otherwise the form starts empty.
     */
    fun start() {
        passwordReset.clear()
        val user = auth.currentUser.value
        if (user != null && !user.isAnonymous) search(user) else _state.value = RestoreUiState()
    }

    fun signIn(email: String, password: String) = signInWith { auth.signInWithEmail(email, password) }

    fun google(idToken: String) = signInWith { auth.signInWithGoogle(idToken) }

    /** What Google's sign-in came back with (ADR-066): closing it says nothing, a failure says so. */
    fun google(result: GoogleSignInResult) {
        when (result) {
            is GoogleSignInResult.Token -> google(result.idToken)
            GoogleSignInResult.Cancelled -> _state.update { it.copy(error = null) }
            GoogleSignInResult.Failed -> _state.update { it.copy(error = AuthError.GoogleSignInFailed) }
        }
    }

    /** "Försök igen" after no answer from the server. */
    fun retry() {
        auth.currentUser.value?.takeIf { !it.isAnonymous }?.let { search(it) }
    }

    /** "Byt konto": signs out, back to the form. */
    fun otherAccount() {
        viewModelScope.launch {
            auth.signOut()
            passwordReset.clear()
            _state.value = RestoreUiState()
        }
    }

    /** Brings [found] back to this phone; the shell then opens the app as soon as onboarding is done. */
    fun choose(found: AccountFamily) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            family.restore(found)
            prefs.set(Prefs.OnboardingDone, true)
            // The screen can open again later in the same app run, after the family is removed from the phone.
            _state.update { it.copy(busy = false) }
        }
    }

    private fun signInWith(block: suspend () -> AuthUser) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                search(block())
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, error = e.error) }
            }
        }
    }

    private fun search(user: AuthUser) {
        _state.update { it.copy(step = RestoreStep.Searching, account = user.email ?: user.displayName, busy = true, error = null) }
        viewModelScope.launch {
            val found = try {
                remote.accountFamilies(user.uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Looking for the account's families failed (${e::class.simpleName})" }
                null
            }
            _state.update {
                it.copy(
                    step = when {
                        found == null -> RestoreStep.Offline
                        found.isEmpty() -> RestoreStep.NoneFound
                        else -> RestoreStep.Found
                    },
                    families = found.orEmpty(),
                    busy = false,
                )
            }
        }
    }
}

/** What the restore screen can ask for; the defaults do nothing, for screenshots. */
data class RestoreActions(
    val onBack: () -> Unit = {},
    val onSignIn: (email: String, password: String) -> Unit = { _, _ -> },
    val onGoogle: (() -> Unit)? = null,
    val onChoose: (AccountFamily) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOtherAccount: () -> Unit = {},
    val onSendPasswordReset: (email: String) -> Unit = {},
)

@Composable
fun RestoreRoute(onBack: () -> Unit, viewModel: RestoreViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passwordReset by viewModel.passwordResetState.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    // Once per visit, not again when the phone is turned.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            viewModel.start()
        }
    }
    RestoreScreen(
        state,
        RestoreActions(
            onBack = onBack,
            onSignIn = viewModel::signIn,
            onGoogle = if (platform.googleSignInAvailable) ({ platform.googleSignIn(viewModel::google) }) else null,
            onChoose = viewModel::choose,
            onRetry = viewModel::retry,
            onOtherAccount = viewModel::otherAccount,
            onSendPasswordReset = viewModel::sendPasswordReset,
        ),
        passwordReset,
    )
}

@Composable
fun RestoreScreen(state: RestoreUiState, actions: RestoreActions, passwordReset: PasswordResetState = PasswordResetState.Idle) {
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.restore_title), onBack = actions.onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.step != RestoreStep.SignIn) {
                state.account?.let { Text(stringResource(Res.string.account_signed_in_as, it), style = MaterialTheme.typography.bodyLarge) }
            }
            when (state.step) {
                RestoreStep.SignIn -> SignInForm(state, actions, passwordReset)
                RestoreStep.Searching -> Searching()
                RestoreStep.Found -> Found(state, actions)
                RestoreStep.NoneFound -> {
                    Text(stringResource(Res.string.restore_none), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = actions.onOtherAccount,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                    ) { Text(stringResource(Res.string.restore_other_account)) }
                }
                RestoreStep.Offline -> {
                    Banner(stringResource(Res.string.account_error_network), kind = BannerKind.Problem)
                    Button(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget)) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInForm(state: RestoreUiState, actions: RestoreActions, passwordReset: PasswordResetState) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Text(stringResource(Res.string.restore_body), style = MaterialTheme.typography.bodyLarge)
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
    state.error?.let {
        Banner(stringResource(errorText(it)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, BannerKind.Problem)
    }
    Button(
        onClick = { actions.onSignIn(email, password) },
        enabled = !state.busy && email.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
    ) { Text(stringResource(Res.string.account_sign_in)) }
    ForgotPassword(email, passwordReset, actions.onSendPasswordReset)
    actions.onGoogle?.let { google ->
        OutlinedButton(
            onClick = google,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
        ) { Text(stringResource(Res.string.account_google)) }
    }
}

@Composable
private fun Searching() {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CircularProgressIndicator()
            Text(
                stringResource(Res.string.restore_searching),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Found(state: RestoreUiState, actions: RestoreActions) {
    Text(stringResource(Res.string.restore_choose), style = MaterialTheme.typography.titleMedium)
    state.families.forEach { found ->
        NavRow(
            found.name.ifBlank { stringResource(Res.string.restore_unnamed) },
            onClick = { if (!state.busy) actions.onChoose(found) },
            supporting = stringResource(if (found.role == Role.Parent) Res.string.role_parent else Res.string.role_caregiver),
        )
    }
    TextButton(onClick = actions.onOtherAccount, enabled = !state.busy) { Text(stringResource(Res.string.restore_other_account)) }
}

/** Only signing in happens here, so a wrong password or no contact are the errors there are. */
private fun errorText(e: AuthError): StringResource = when (e) {
    AuthError.InvalidCredentials -> Res.string.account_error_credentials
    AuthError.GoogleSignInFailed -> Res.string.account_error_google
    AuthError.EmailInUse, AuthError.WeakPassword, AuthError.Network, AuthError.Unavailable, AuthError.Unknown ->
        Res.string.account_error_network
}

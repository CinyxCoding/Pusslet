package se.pusslet.data.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.pusslet.core.account.PasswordResetState
import se.pusslet.data.remote.AuthError
import se.pusslet.data.remote.AuthException
import se.pusslet.data.remote.AuthRepository

/**
 * "Glömt lösenordet?" (ADR-064), for every place with a sign-in form: Firebase mails a link where a new password is chosen.
 * Without this, a forgotten password would lock someone out of the account and the family for good.
 */
class PasswordReset(private val auth: AuthRepository) {
    private val _state = MutableStateFlow(PasswordResetState.Idle)
    val state: StateFlow<PasswordResetState> = _state.asStateFlow()

    suspend fun send(email: String) {
        if (_state.value == PasswordResetState.Sending) return
        val address = email.trim()
        if (!LOOKS_LIKE_EMAIL.matches(address)) {
            _state.value = PasswordResetState.BadEmail
            return
        }
        _state.value = PasswordResetState.Sending
        _state.value = try {
            auth.sendPasswordReset(address)
            PasswordResetState.Sent
        } catch (e: AuthException) {
            if (e.error == AuthError.InvalidCredentials) PasswordResetState.BadEmail else PasswordResetState.Failed
        }
    }

    /** The screen opened again, or another account is used: what was said about the last address no longer applies. */
    fun clear() {
        _state.value = PasswordResetState.Idle
    }

    private companion object {
        /** Only catches what is obviously not an address; Firebase decides the rest. */
        val LOOKS_LIKE_EMAIL = Regex("""[^@\s]+@[^@\s]+""")
    }
}

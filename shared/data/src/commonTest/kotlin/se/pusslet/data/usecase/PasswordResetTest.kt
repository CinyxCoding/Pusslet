package se.pusslet.data.usecase

import kotlinx.coroutines.test.runTest
import se.pusslet.core.account.PasswordResetState
import se.pusslet.data.remote.AuthError
import se.pusslet.data.remote.AuthException
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.LocalOnlyAuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Glömt lösenordet?" (ADR-064). */
class PasswordResetTest {
    private class Mailer(val failure: AuthError? = null) : AuthRepository by LocalOnlyAuthRepository() {
        val sentTo = mutableListOf<String>()

        override suspend fun sendPasswordReset(email: String) {
            failure?.let { throw AuthException(it) }
            sentTo += email
        }
    }

    @Test
    fun theLinkGoesToTheAddressWithoutStraySpaces() = runTest {
        val mailer = Mailer()
        val reset = PasswordReset(mailer)

        reset.send("  sara@example.se ")

        assertEquals(listOf("sara@example.se"), mailer.sentTo)
        assertEquals(PasswordResetState.Sent, reset.state.value)
    }

    @Test
    fun whatIsNotAnAddressIsNeverSent() = runTest {
        val mailer = Mailer()
        val reset = PasswordReset(mailer)

        listOf("", "  ", "sara", "sara@", "@example.se", "sa ra@example.se").forEach { address ->
            reset.send(address)
            assertEquals(PasswordResetState.BadEmail, reset.state.value, address)
        }

        assertEquals(emptyList(), mailer.sentTo)
    }

    @Test
    fun anAddressTheServerRefusesAsksToCheckIt() = runTest {
        val reset = PasswordReset(Mailer(AuthError.InvalidCredentials))

        reset.send("sara@example")

        assertEquals(PasswordResetState.BadEmail, reset.state.value)
    }

    @Test
    fun noContactWithTheServerSaysSo() = runTest {
        val reset = PasswordReset(Mailer(AuthError.Network))

        reset.send("sara@example.se")

        assertEquals(PasswordResetState.Failed, reset.state.value)
    }

    @Test
    fun clearingStartsOver() = runTest {
        val reset = PasswordReset(Mailer())
        reset.send("sara@example.se")

        reset.clear()

        assertEquals(PasswordResetState.Idle, reset.state.value)
    }
}

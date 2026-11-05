package se.pusslet.android.platform

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import org.junit.Test
import se.pusslet.ui.platform.GoogleSignInResult
import kotlin.test.assertEquals

/** Which ending of Google's sign-in the screen stays quiet about (ADR-066). */
class GoogleSignInResultTest {
    @Test
    fun closingItIsNotAnError() {
        assertEquals(GoogleSignInResult.Cancelled, googleSignInResultOf(GetCredentialCancellationException()))
    }

    /** No account on the phone was the case that made the button seem dead: it counted as closed. */
    @Test
    fun everythingElseIsSaid() {
        listOf(
            NoCredentialException(),
            GetCredentialUnknownException(),
            GetCredentialInterruptedException(),
            GetCredentialProviderConfigurationException(),
        ).forEach { assertEquals(GoogleSignInResult.Failed, googleSignInResultOf(it), it::class.simpleName) }
    }
}

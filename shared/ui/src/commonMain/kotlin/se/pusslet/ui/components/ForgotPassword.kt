package se.pusslet.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.account.PasswordResetState
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.account_error_network
import se.pusslet.ui.resources.account_forgot_password
import se.pusslet.ui.resources.password_reset_no_email
import se.pusslet.ui.resources.password_reset_sent

/**
 * "Glömt lösenordet?" under a sign-in form (ADR-064): sends the link to the address in the form's [email] field and says how it
 * went right there. The receipt reads the same whether or not there is an account, so nobody can use it to find out who uses
 * the app.
 */
@Composable
fun ForgotPassword(email: String, state: PasswordResetState, onSend: (String) -> Unit) {
    TextButton(onClick = { onSend(email) }, enabled = state != PasswordResetState.Sending) {
        Text(stringResource(Res.string.account_forgot_password))
    }
    // Said by TalkBack when it shows, since the button itself does not change.
    val spoken = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        PasswordResetState.Sent -> Banner(stringResource(Res.string.password_reset_sent), spoken, BannerKind.Info)
        PasswordResetState.BadEmail -> Banner(stringResource(Res.string.password_reset_no_email), spoken, BannerKind.Problem)
        PasswordResetState.Failed -> Banner(stringResource(Res.string.account_error_network), spoken, BannerKind.Problem)
        PasswordResetState.Idle, PasswordResetState.Sending -> Unit
    }
}

package se.pusslet.android.screenshots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.core.account.PasswordResetState
import se.pusslet.core.model.Role
import se.pusslet.data.remote.AccountFamily
import se.pusslet.feature.family.restore.RestoreActions
import se.pusslet.feature.family.restore.RestoreScreen
import se.pusslet.feature.family.restore.RestoreStep
import se.pusslet.feature.family.restore.RestoreUiState
import se.pusslet.ui.theme.PussletTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Jag har redan ett konto" on a new phone (ADR-063): the sign-in form, and the family the account belongs to. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class RestoreScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val lind = AccountFamily("f-lind", "Familjen Lind", "m-sara", Role.Parent)

    /** The screenshot, and the accessibility audit of the same screen (named, 48 dp controls). */
    private fun snap(name: String) {
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        val problems = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map(AccessibilityAudit::audit).flatMap { it.problems }
        assertTrue(problems.isEmpty(), "Accessibility on $name:\n" + problems.joinToString("\n"))
    }

    @Test
    fun signIn() {
        compose.setContent { PussletTheme { RestoreScreen(RestoreUiState(), RestoreActions(onGoogle = {})) } }
        compose.onNodeWithText("Logga in med samma konto som på din förra telefon, så hämtas familjen hit.").assertExists()
        snap("restore_sign_in")
    }

    /** "Glömt lösenordet?" uses the address in the form, and the receipt says nothing about who has an account (ADR-064). */
    @Test
    fun aForgottenPasswordGoesToTheAddressInTheForm() {
        val sent = mutableListOf<String>()
        var reset by mutableStateOf(PasswordResetState.Idle)
        compose.setContent {
            PussletTheme {
                val actions = RestoreActions(
                    onSendPasswordReset = {
                        sent += it
                        reset = if (it.isBlank()) PasswordResetState.BadEmail else PasswordResetState.Sent
                    },
                )
                RestoreScreen(RestoreUiState(), actions, reset)
            }
        }

        compose.onNodeWithText("Glömt lösenordet?").performClick()
        compose.onNodeWithText("Skriv din e-postadress ovan", substring = true).assertExists()

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("sara@example.se")
        compose.onNodeWithText("Glömt lösenordet?").performClick()

        assertEquals(listOf("", "sara@example.se"), sent)
        compose.onNodeWithText("Om det finns ett konto för adressen", substring = true).assertExists()
        snap("restore_password_sent")
    }

    @Test
    fun theAccountsFamilyIsOneTapAway() {
        val chosen = mutableListOf<AccountFamily>()
        val state = RestoreUiState(step = RestoreStep.Found, families = listOf(lind), account = "sara@example.se")
        compose.setContent { PussletTheme { RestoreScreen(state, RestoreActions(onChoose = { chosen += it })) } }
        compose.onNodeWithText("Inloggad som sara@example.se").assertExists()
        snap("restore_found")

        compose.onNodeWithText("Familjen Lind").performClick()

        assertEquals(listOf(lind), chosen)
    }
}

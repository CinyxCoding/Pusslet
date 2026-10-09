package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.PussletApp

/** Kalas and presents (V2) with the demo family: the party card on "Kalas hos Vera" and Ella's wishlist. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class PartyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun launch() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 10_000)

    @Test
    fun partyCard() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        waitFor("Vecka 41")
        compose.waitForIdle()
        compose.onAllNodesWithText("Kalas hos Vera").onFirst().performClick()
        waitFor("Presentidé")
        compose.onNodeWithText("Presenten är köpt").performScrollTo()
        compose.onRoot().captureRoboImage("src/test/snapshots/party_details.png")
    }

    @Test
    fun wishlist() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        waitFor("Familjen")
        compose.onNodeWithText("Familjen").performClick()
        waitFor("Önskelistor")
        // Ella's row in the wishlist card: two wishes nobody has bought yet.
        compose.onNodeWithText("2 önskningar").performScrollTo().performClick()
        waitFor("Ellas önskelista")
        waitFor("Köpt av Bengt")
        compose.onRoot().captureRoboImage("src/test/snapshots/wishlist.png")
    }
}

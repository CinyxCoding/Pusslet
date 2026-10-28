package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.PussletApp

/** "Andra hemmet" (ADR-030) from the family screen, in the app without a server: what sharing does and why it is off. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class OtherHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 15_000)

    @Test
    fun otherHome() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        waitFor("Ella")
        compose.waitForIdle()
        compose.onNodeWithText("Mer").performClick()
        waitFor("Familjen")
        compose.onNodeWithText("Familjen").performClick()
        waitFor("Vuxna")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Andra hemmet"))
        compose.onNodeWithText("Öppna").performClick()
        waitFor("Bor barnen i två hem")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/other_home.png")
    }
}

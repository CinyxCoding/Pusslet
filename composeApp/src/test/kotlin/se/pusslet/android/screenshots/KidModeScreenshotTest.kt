package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.PussletApp

/** Barnläge and veckopeng (V2) with the demo family on Thursday morning. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class KidModeScreenshotTest {
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
    fun kidMode() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        waitFor("Barnläge")
        compose.onNodeWithText("Barnläge").performClick()
        waitFor("Vem ska använda telefonen?")
        compose.onNodeWithText("Ella").performClick()
        waitFor("Hej Ella!")
        waitFor("Duka av efter middagen")
        compose.onRoot().captureRoboImage("src/test/snapshots/kid_mode.png")
    }

    @Test
    fun approvalsAndPocketMoney() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        waitFor("Uppgifter och sysslor")
        compose.onNodeWithText("Uppgifter och sysslor").performClick()
        waitFor("Godkänn")
        compose.onRoot().captureRoboImage("src/test/snapshots/tasks_approvals.png")
        compose.onNodeWithText("Veckopeng").performClick()
        waitFor("Kr per stjärna")
        compose.onRoot().captureRoboImage("src/test/snapshots/allowance.png")
    }
}

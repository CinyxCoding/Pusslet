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

/** The demo family's weekly shop, sorted for ICA Nära, and store mode. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ShoppingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun openShopping() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.onNodeWithText("Handla").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Bananer"), timeoutMillis = 10_000)
        compose.waitForIdle()
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun list() {
        openShopping()
        compose.onNodeWithText("Frukt och grönt").assertExists()
        snap("shopping_list")
    }

    @Test
    @Config(qualifiers = "+night")
    fun listDark() {
        openShopping()
        snap("shopping_list_dark")
    }

    @Test
    fun storeMode() {
        openShopping()
        compose.onNodeWithText("Butiksläge").performClick()
        compose.waitUntilAtLeastOneExists(hasText("kvar", substring = true), timeoutMillis = 10_000)
        compose.onNodeWithText("Bananer").performClick()
        // Checked items drop into the trolley: the count goes down.
        compose.waitUntilAtLeastOneExists(hasText("4 kvar", substring = true), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("shopping_store_mode")
    }
}

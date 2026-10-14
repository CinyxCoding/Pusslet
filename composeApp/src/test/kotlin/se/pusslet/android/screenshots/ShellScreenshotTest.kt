package se.pusslet.android.screenshots

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

/**
 * Screenshots of the real app with the demo family (Familjen Lind) on a fixed morning: light, dark, English and
 * large text. They double as smoke tests – each one asserts the key content is there.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ShellScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun launch(seed: Boolean = true, waitFor: String = "Ella") {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText(waitFor, substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    /** A bottom-bar tab; "Mat" alone would also match the meals heading on Idag. */
    private fun openTab(label: String) =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()

    @Test
    fun todayLight() {
        launch()
        compose.onNodeWithText("Idag").assertExists()
        snap("today_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun todayDark() {
        launch()
        snap("today_dark")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun todayEnglish() {
        launch()
        compose.onNodeWithText("Today").assertExists()
        snap("today_en")
    }

    @Test
    @Config(fontScale = 1.5f)
    fun todayLargeText() {
        launch()
        snap("today_large_text")
    }

    @Test
    fun calendarWeek() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
        snap("calendar_week")
    }

    @Test
    fun calendarMonth() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        compose.onNodeWithText("Månad").performClick()
        // The year only shows when it is not this year (UX plan 1.1).
        compose.waitUntilAtLeastOneExists(hasText("Oktober"), timeoutMillis = 10_000)
        snap("calendar_month")
    }

    @Test
    fun eventDetails() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
        // School and preschool are quiet lines ("Simon: Johan lämnar 07:50 · …"); a tap opens the event.
        compose.onAllNodesWithText("lämnar 07:50", substring = true).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Lämning och hämtning"), timeoutMillis = 10_000)
        snap("event_details")
    }

    @Test
    @Config(fontScale = 1.5f)
    fun calendarWeekLargeText() {
        launch()
        openTab("Kalender")
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
        snap("calendar_week_large_text")
    }

    @Test
    fun mealsWeek() {
        launch()
        openTab("Mat")
        compose.waitUntilAtLeastOneExists(hasText("Matsedel"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("meals_week")
    }

    @Test
    fun shoppingList() {
        launch()
        openTab("Handla")
        compose.waitUntilAtLeastOneExists(hasText("Veckohandling"), timeoutMillis = 10_000)
        snap("shopping_list")
    }

    @Test
    fun storeMode() {
        launch()
        openTab("Handla")
        compose.waitUntilAtLeastOneExists(hasText("Jag handlar nu"), timeoutMillis = 10_000)
        compose.onNodeWithText("Jag handlar nu").performClick()
        // Store mode is a sub page: the back arrow is what tells it apart from the list.
        compose.waitUntilAtLeastOneExists(hasContentDescription("Tillbaka"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("store_mode")
    }

    @Test
    fun more() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        compose.waitForIdle()
        snap("more")
    }

    @Test
    fun onboarding() {
        launch(seed = false, waitFor = "Pusslet")
        snap("onboarding_welcome")
    }
}

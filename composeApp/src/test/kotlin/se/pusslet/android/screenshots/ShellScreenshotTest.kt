package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
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
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41"), timeoutMillis = 10_000)
        snap("calendar_week")
    }

    @Test
    fun calendarMonth() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        // The tab composes asynchronously; under a full build the view switcher can be a frame late.
        compose.waitUntilAtLeastOneExists(hasText("Månad"), timeoutMillis = 10_000)
        compose.onNodeWithText("Månad").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Oktober 2026"), timeoutMillis = 10_000)
        snap("calendar_month")
    }

    @Test
    fun eventDetails() {
        launch()
        compose.onNodeWithText("Kalender").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Skola", substring = true).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Lämning och hämtning"), timeoutMillis = 10_000)
        snap("event_details")
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

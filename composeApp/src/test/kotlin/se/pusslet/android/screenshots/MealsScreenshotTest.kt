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

/** The Mat tab with the demo family's week 41: clashes with the school lunch, a double batch and a busy Tuesday. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MealsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun openMeals() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.onNodeWithText("Mat").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Köttbullar med potatismos"), timeoutMillis = 10_000)
        compose.waitForIdle()
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun week() {
        openMeals()
        compose.onNodeWithText("Vecka 41").assertExists()
        compose.onAllNodesWithText("har redan ätit", substring = true).onFirst().assertExists()
        snap("meals_week")
    }

    @Test
    @Config(qualifiers = "+night")
    fun weekDark() {
        openMeals()
        snap("meals_week_dark")
    }

    @Test
    @Config(fontScale = 1.5f)
    fun weekLargeText() {
        openMeals()
        snap("meals_week_large_text")
    }

    @Test
    fun daySheet() {
        openMeals()
        // Tuesday: a clash with the school lunch and a meeting at dinner time, so quick dishes come first.
        compose.onNodeWithText("Fiskgratäng med potatis").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Förslag"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("meals_day_sheet")
    }

    @Test
    fun recipe() {
        openMeals()
        compose.onNodeWithText("Recept").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Fredagstacos"), timeoutMillis = 10_000)
        snap("recipes_library")
        compose.onNodeWithText("Fredagstacos").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ingredienser"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("recipe_view")
    }
}

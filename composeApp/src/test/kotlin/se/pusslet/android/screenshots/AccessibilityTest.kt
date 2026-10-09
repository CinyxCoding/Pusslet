package se.pusslet.android.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.PussletApp
import kotlin.test.assertEquals

/**
 * Accessibility checks on the main views of the real app – every tappable thing has a name TalkBack can read and a touch
 * area of at least 48 dp – plus screenshots at 200 % text size, where layouts most often break. (The Accessibility Test
 * Framework does not report anything under Robolectric, so the checks walk the Compose semantics tree directly.
 * Contrast is covered by the palette tests.)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class AccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun launch() {
        TestEnvironment.start(compose.activity.applicationContext, seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    private fun tab(name: String, waitFor: String) {
        compose.onNodeWithText(name).performClick()
        compose.waitUntilAtLeastOneExists(hasText(waitFor, substring = true), timeoutMillis = 10_000)
        compose.waitForIdle()
    }

    private fun check() {
        compose.waitForIdle()
        // Nodes with empty bounds are composed but not on screen (e.g. a pager page out of view).
        val problems = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { !it.boundsInRoot.isEmpty }.mapNotNull { node ->
            val config = node.config
            val label = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString() +
                config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString() +
                config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            val density = compose.density
            val bounds = node.touchBoundsInRoot
            val small = with(density) { bounds.width.toDp() < MIN_TOUCH.dp || bounds.height.toDp() < MIN_TOUCH.dp }
            when {
                label.isBlank() -> "no label at $bounds"
                small -> "\"$label\" is only ${with(density) { bounds.width.toDp() }} × ${with(density) { bounds.height.toDp() }}"
                else -> null
            }
        }
        assertEquals(emptyList(), problems)
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun mainViewsPassTheAccessibilityChecks() {
        launch()
        check()
        tab("Kalender", "Vecka 41")
        check()
        tab("Mat", "Köttbullar med potatismos")
        check()
        tab("Handla", "Bananer")
        check()
        tab("Mer", "Inställningar")
        check()
    }

    @Test
    fun eventDetailsPassTheAccessibilityChecks() {
        launch()
        tab("Kalender", "Vecka 41")
        compose.onAllNodesWithText("Skola", substring = true).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Lämning och hämtning"), timeoutMillis = 10_000)
        check()
    }

    @Test
    @Config(fontScale = 2f)
    fun todayAtDoubleTextSize() {
        launch()
        snap("today_text_200")
    }

    @Test
    @Config(fontScale = 2f)
    fun calendarAtDoubleTextSize() {
        launch()
        tab("Kalender", "Vecka 41")
        snap("calendar_week_text_200")
    }

    @Test
    @Config(fontScale = 2f)
    fun shoppingAtDoubleTextSize() {
        launch()
        tab("Handla", "Bananer")
        snap("shopping_text_200")
    }

    @Test
    @Config(qualifiers = "+night")
    fun calendarDark() {
        launch()
        tab("Kalender", "Vecka 41")
        snap("calendar_week_dark")
    }
}

private const val MIN_TOUCH = 47.5f

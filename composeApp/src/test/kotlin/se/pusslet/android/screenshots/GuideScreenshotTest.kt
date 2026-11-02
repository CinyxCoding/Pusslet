package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config
import se.pusslet.data.repository.GuideStatus
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.shell.PussletApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guide through the app after the first start, and "Vanliga frågor" (ADR-062), in the real app with the demo family:
 * forward, back, "Stäng", "Visa inte igen" and "Klar", and what each one leaves for the next start.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class GuideScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun launch(guide: Boolean = true, asBengt: Boolean = false, waitFor: String = "Så funkar Pusslet") {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true, asBengt = asBengt, guide = guide)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText(waitFor, substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    /** The screenshot, and the accessibility audit of the same screen (named, 48 dp controls). */
    private fun snap(name: String) {
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        val audits = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map(AccessibilityAudit::audit)
        assertTrue(audits.sumOf { it.checked } > 0, "The audit of $name found no controls at all")
        val problems = audits.flatMap { it.problems }
        assertTrue(problems.isEmpty(), "Accessibility on $name:\n" + problems.joinToString("\n"))
    }

    private fun guideStatus(): GuideStatus = runBlocking { GlobalContext.get().get<PreferencesRepository>().get(Prefs.Guide) }

    /** Idag is behind the guide: the demo family's day. */
    private fun waitForToday() = compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)

    @Test
    fun theGuideOpensAfterTheFirstStart() {
        launch()
        compose.onNodeWithText("1 av 14").assertExists()
        compose.onNodeWithText("Visa inte igen").assertExists()
        snap("guide_first_page")
    }

    @Test
    fun forwardAndBackThenDontShowAgain() {
        launch()
        compose.onNodeWithText("Nästa").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Dagen på en skärm"), timeoutMillis = 10_000)
        compose.onNodeWithText("2 av 14").assertExists()
        compose.onNodeWithText("Finns under Idag").assertExists()

        compose.onNodeWithText("Tillbaka").performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 av 14"), timeoutMillis = 10_000)

        compose.onNodeWithText("Visa inte igen").performClick()
        waitForToday()
        assertEquals(GuideStatus.Done, guideStatus())
    }

    /** "Stäng" only closes it for now: the guide is still due at the next start. */
    @Test
    fun closingKeepsItForTheNextStart() {
        launch()
        compose.onNodeWithContentDescription("Stäng").performClick()
        waitForToday()
        assertEquals(GuideStatus.Due, guideStatus())
    }

    @Test
    fun klarOnTheLastPageClosesItForGood() {
        launch()
        repeat(13) {
            compose.onNodeWithText("Nästa").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("14 av 14").assertExists()
        compose.onNodeWithText("Klar").performClick()
        waitForToday()
        assertEquals(GuideStatus.Done, guideStatus())
    }

    /** Morfar Bengt gets the guide to the helper view, in its larger type (ADR-029). */
    @Test
    fun anotherAdultGetsTheHelperGuide() {
        launch(asBengt = true)
        compose.onNodeWithText("1 av 6").assertExists()
        snap("guide_helper")
    }

    /** Mer › Inställningar › Hjälp › Vanliga frågor, with one answer open; the guide opens again from there. */
    @Test
    fun commonQuestionsFromSettings() {
        launch(guide = false, waitFor = "Ella")
        compose.onNodeWithText("Mer").performClick()
        compose.onNodeWithText("Inställningar").performClick()
        compose.onNodeWithText("Vanliga frågor").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("De andra ser inte det jag har lagt in"), timeoutMillis = 10_000)
        compose.onNodeWithText("De andra ser inte det jag har lagt in").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Alla behöver vara inloggade", substring = true), timeoutMillis = 10_000)
        snap("faq")

        // The button is the list's last item, so the list scrolls to it before it exists.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Visa guiden"))
        compose.onNodeWithText("Visa guiden").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Så funkar Pusslet"), timeoutMillis = 10_000)
        // Opened from Hjälp, the guide has no "Visa inte igen": it does not open by itself anyway.
        compose.onNodeWithText("Visa inte igen").assertDoesNotExist()
    }
}

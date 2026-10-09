package se.pusslet.android.flows

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config
import se.pusslet.android.screenshots.TestEnvironment
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.shell.PussletApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flows the brief calls critical, end to end through the real app: onboarding, quick add, a shopping list from the
 * meal plan, and store mode. Swedish UI, the demo morning (Thursday 8 October 2026, 07:10).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class CriticalFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun launch(seed: Boolean, waitFor: String) {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText(waitFor, substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    /** Waits for [text]; on time-out the failure shows what was on screen instead. */
    private fun waitFor(text: String) {
        try {
            compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 10_000)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("\"$text\" never showed up. On screen:\n" + compose.onRoot().printToString(), e)
        }
    }

    private fun tap(text: String) {
        waitFor(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    @Test
    fun onboardingTakesFourShortSteps() {
        launch(seed = false, waitFor = "Skapa vår familj")
        tap("Skapa vår familj")
        waitFor("Vilka är ni?")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Sara")
        tap("Nästa")
        waitFor("Vilka barn finns i familjen?")
        tap("Lägg till barn")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Ella")
        tap("Nästa")
        waitFor("Var bor ni?")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Uppsala")
        compose.onNode(hasText("Uppsala") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        tap("Nästa")
        waitFor("Klart!")
        tap("Kom igång")
        waitFor("God morgon, Sara")
        val children = runBlocking { koin<FamilyRepository>().children.first { it.isNotEmpty() } }
        assertEquals(listOf("Ella"), children.map { it.firstName })
        val place = runBlocking { koin<FamilyRepository>().settings.first { it.weatherPlace != null }.weatherPlace }
        assertEquals("Uppsala", place?.name)
    }

    @Test
    fun quickAddTurnsASentenceIntoAnEvent() {
        launch(seed = true, waitFor = "Ella")
        // The + button is announced as "Snabbinmatning: skriv vad som ska hända".
        compose.onNodeWithContentDescription("Snabbinmatning", substring = true).performClick()
        waitFor("Vad ska hända?")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Simon logoped tisdag 14:30")
        waitFor("Logoped")
        compose.onNode(hasText("Spara") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performClick()
        waitFor("Sparat!")
        val speech = runBlocking { koin<CalendarRepository>().events.first { list -> list.any { it.title == "Logoped" } } }
            .first { it.title == "Logoped" }
        assertEquals("2026-10-13T14:30", speech.start.toString())
        assertTrue(speech.participantIds.isNotEmpty(), "Simon is in it")
    }

    @Test
    fun theMealPlanFillsTheShoppingList() {
        launch(seed = true, waitFor = "Ella")
        val before = runBlocking { koin<ShoppingRepository>().items.first().count { !it.checked } }
        compose.onNodeWithText("Mat").performClick()
        tap("Handla till veckan")
        tap("Lägg på listan")
        waitFor("är uppdaterad")
        val after = runBlocking { koin<ShoppingRepository>().items.first().count { !it.checked } }
        assertTrue(after > before, "ingredients were added ($before -> $after)")
    }

    @Test
    fun storeModeTicksEverythingOff() {
        launch(seed = true, waitFor = "Ella")
        compose.onNodeWithText("Handla").performClick()
        tap("Butiksläge")
        waitFor("kvar")
        listOf("Bananer", "Gurka", "Mjölk", "Kaffe", "Blöjor storlek 5").forEach { tap(it) }
        waitFor("Allt på listan är i vagnen.")
    }

    @Test
    fun searchFindsAnEventAndOpensIt() {
        launch(seed = true, waitFor = "Ella")
        compose.onNodeWithContentDescription("Sök").performClick()
        waitFor("Sök i familjens kalender")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("fotbol")
        waitFor("Händelser")
        tap("Fotboll")
        waitFor("Lämning och hämtning")
    }
}

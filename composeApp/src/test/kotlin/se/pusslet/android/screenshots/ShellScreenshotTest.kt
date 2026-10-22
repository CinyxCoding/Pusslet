package se.pusslet.android.screenshots

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.shell.PussletApp
import kotlin.test.assertTrue

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

    private fun launch(seed: Boolean = true, waitFor: String = "Ella", asBengt: Boolean = false) {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed, asBengt)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText(waitFor, substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    /** The screenshot, and the accessibility audit of the same screen (named, 48 dp controls). */
    private fun snap(name: String) {
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        // Every window: the screen, and a dialog or sheet on top of it.
        val audits = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map(AccessibilityAudit::audit)
        assertTrue(audits.sumOf { it.checked } > 0, "The audit of $name found no controls at all")
        val problems = audits.flatMap { it.problems }
        assertTrue(problems.isEmpty(), "Accessibility on $name:\n" + problems.joinToString("\n"))
    }

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
        // The calendar draws nothing until its state has loaded from Room, which Compose's idling does not wait for.
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
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

    /** An empty week offers "Planera veckan" and, with last week planned, "Kopiera förra veckan" (UX plan 4.5). */
    @Test
    fun mealsEmptyWeek() {
        launch()
        openTab("Mat")
        compose.waitUntilAtLeastOneExists(hasText("Matsedel"), timeoutMillis = 10_000)
        repeat(2) {
            compose.onAllNodesWithContentDescription("Nästa").onFirst().performClick()
            compose.waitForIdle()
        }
        compose.waitUntilAtLeastOneExists(hasText("Planera veckan"), timeoutMillis = 10_000)
        snap("meals_empty_week")
    }

    @Test
    fun shoppingEmpty() {
        launch()
        runBlocking {
            val shopping = GlobalContext.get().get<ShoppingRepository>()
            shopping.getItems("l-vecka").forEach { shopping.deleteItem(it.id) }
        }
        openTab("Handla")
        compose.waitUntilAtLeastOneExists(hasText("Listan är tom"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("shopping_empty")
    }

    @Test
    fun recipesEmpty() {
        launch()
        runBlocking {
            val recipes = GlobalContext.get().get<RecipeRepository>()
            recipes.all().forEach { recipes.delete(it.id) }
        }
        openTab("Mat")
        compose.waitUntilAtLeastOneExists(hasText("Matsedel"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Recept").onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Inga recept än"), timeoutMillis = 10_000)
        snap("recipes_empty")
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

    /** Morfar Bengt's phone: the simple helper view, with no bottom bar and no round + (UX plan Steg 3). */
    @Test
    fun helperHome() {
        launch(waitFor = "Du hämtar", asBengt = true)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
        // The demo's request from Sara comes first, with Ja/Nej (UX plan Steg 3, H3).
        compose.waitUntilAtLeastOneExists(hasText("Ja, jag hämtar Ella"), timeoutMillis = 10_000)
        snap("helper_home")
    }

    /** "Så ser Bengt appen": a parent previews the helper view; nothing tapped there is saved (UX plan Steg 3, H3). */
    @Test
    fun helperPreview() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        compose.onNodeWithText("Familjen").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Så ser", substring = true) and hasClickAction(), timeoutMillis = 10_000)
        compose.onNode(hasText("Så ser", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Förhandsvisning. Det du trycker på här sparas inte."), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Du hämtar", substring = true), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("helper_preview")
    }

    @Test
    @Config(qualifiers = "+night")
    fun helperHomeDark() {
        launch(waitFor = "Du hämtar", asBengt = true)
        snap("helper_home_dark")
    }

    @Test
    @Config(fontScale = 2.0f)
    fun helperHomeLargeText() {
        launch(waitFor = "Du hämtar", asBengt = true)
        snap("helper_home_large_text")
    }

    /** Mer › Uppgifter och sysslor: mine first, then what nobody has (Fas 3). */
    @Test
    fun tasksList() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        tap("Uppgifter och sysslor")
        compose.waitUntilAtLeastOneExists(hasText("Mina"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("tasks_list")
    }

    /** A rotating chore in the editor: "Vems tur" with "Byt vecka". */
    @Test
    fun taskEditor() {
        launch()
        compose.onNodeWithText("Mer").performClick()
        tap("Uppgifter och sysslor")
        compose.waitUntilAtLeastOneExists(hasText("Dammsuga", substring = true) and hasClickAction(), timeoutMillis = 10_000)
        compose.onAllNodes(hasText("Dammsuga", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Vems tur"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("task_editor")
    }

    @Test
    fun onboarding() {
        launch(seed = false, waitFor = "Pusslet")
        snap("onboarding_welcome")
    }

    /** First start up to step 1 with the name typed in ("Skapa vår familj", "Ditt namn"). */
    private fun startOnboarding(create: String = "Skapa vår familj", yourName: String = "Ditt namn") {
        launch(seed = false, waitFor = "Pusslet")
        compose.onNodeWithText(create).performClick()
        compose.waitUntilAtLeastOneExists(hasText(yourName) and hasSetTextAction(), timeoutMillis = 10_000)
        compose.onNode(hasText(yourName) and hasSetTextAction()).performTextInput("Sara")
    }

    private fun tap(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()

    /** Step 1 asks who else drops off and picks up; names only (UX plan 4.1). */
    @Test
    fun onboardingFamily() {
        startOnboarding()
        compose.onNode(hasText("Namn") and hasSetTextAction()).performTextInput("Johan")
        tap("Lägg till")
        compose.waitUntilAtLeastOneExists(hasContentDescription("Ta bort Johan"), timeoutMillis = 10_000)
        snap("onboarding_family")
    }

    /** Step 2 starts with one empty child row (UX plan 4.2). */
    @Test
    fun onboardingChildren() {
        startOnboarding()
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Vilka barn finns i familjen?"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("onboarding_children")
    }

    /** Step 3: the search comes first and an empty field lists no places (UX plan 4.2). */
    @Test
    fun onboardingPlaceEmpty() {
        startOnboarding()
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Vilka barn finns i familjen?"), timeoutMillis = 10_000)
        tap("Hoppa över")
        compose.waitUntilAtLeastOneExists(hasText("Använd min position"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Stockholm").assertCountEquals(0)
        snap("onboarding_place_empty")
    }

    /** "Klart!" with one sentence and "Visa min dag" (UX plan 4.3). */
    @Test
    fun onboardingDone() {
        startOnboarding()
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Vilka barn finns i familjen?"), timeoutMillis = 10_000)
        tap("Hoppa över")
        compose.waitUntilAtLeastOneExists(hasText("Använd min position"), timeoutMillis = 10_000)
        tap("Hoppa över")
        compose.waitUntilAtLeastOneExists(hasText("Klart!"), timeoutMillis = 10_000)
        snap("onboarding_done")
    }

    /** A new family right after first start: "Kom igång" ticks itself off as the family fills in (UX plan 4.4). */
    @Test
    fun todayNewFamily() {
        startOnboarding()
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Vilka barn finns i familjen?"), timeoutMillis = 10_000)
        compose.onNode(hasText("Barnets förnamn") and hasSetTextAction()).performTextInput("Ella")
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Använd min position"), timeoutMillis = 10_000)
        tap("Hoppa över")
        compose.waitUntilAtLeastOneExists(hasText("Klart!"), timeoutMillis = 10_000)
        tap("Visa min dag")
        compose.waitUntilAtLeastOneExists(hasText("Kom igång"), timeoutMillis = 15_000)
        compose.waitForIdle()
        snap("today_new_family")
    }

    /** Quick add in a real family: example chips with the family's own child, never the example family (UX plan 4.6). */
    @Test
    fun quickAddNewFamily() {
        startOnboarding()
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Vilka barn finns i familjen?"), timeoutMillis = 10_000)
        compose.onNode(hasText("Barnets förnamn") and hasSetTextAction()).performTextInput("Noah")
        tap("Nästa")
        compose.waitUntilAtLeastOneExists(hasText("Använd min position"), timeoutMillis = 10_000)
        tap("Hoppa över")
        compose.waitUntilAtLeastOneExists(hasText("Klart!"), timeoutMillis = 10_000)
        tap("Visa min dag")
        compose.waitUntilAtLeastOneExists(hasText("Kom igång"), timeoutMillis = 15_000)
        compose.onNode(hasContentDescription("Lägg till") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Noah dans tis 17:00"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Ella", substring = true).assertCountEquals(0)
        compose.waitForIdle()
        snap("quickadd_new_family")
    }

    /** The example family can be left from Idag, after a confirmation (UX plan 4.8). */
    @Test
    fun todayLeaveDemo() {
        launch()
        tap("Börja på riktigt")
        compose.waitUntilAtLeastOneExists(hasText("Börja på riktigt?"), timeoutMillis = 10_000)
        snap("today_leave_demo")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun onboardingDoneEnglish() {
        startOnboarding(create = "Create our family", yourName = "Your name")
        tap("Next")
        compose.waitUntilAtLeastOneExists(hasText("Skip"), timeoutMillis = 10_000)
        tap("Skip")
        compose.waitUntilAtLeastOneExists(hasText("Use my location"), timeoutMillis = 10_000)
        tap("Skip")
        compose.waitUntilAtLeastOneExists(hasText("All set!"), timeoutMillis = 10_000)
        snap("onboarding_done_en")
    }

    // Screens opened for the accessibility audit too (AccessibilityAudit): settings, the family, editors and sheets.

    @Test
    fun settings() {
        launch()
        openMore("Inställningar")
        compose.waitUntilAtLeastOneExists(hasText("Notiser", substring = true), timeoutMillis = 10_000)
        snap("settings")
    }

    @Test
    fun family() {
        launch()
        openMore("Familjen")
        compose.waitUntilAtLeastOneExists(hasText("Så ser", substring = true), timeoutMillis = 10_000)
        snap("family")
    }

    @Test
    fun childEditor() {
        launch()
        openMore("Familjen")
        compose.waitUntilAtLeastOneExists(hasText("Ella") and hasClickAction(), timeoutMillis = 10_000)
        compose.onAllNodes(hasText("Ella", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Allergier och kost"), timeoutMillis = 10_000)
        snap("child_editor")
    }

    @Test
    fun eventEditor() {
        launch()
        openTab("Kalender")
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("lämnar 07:50", substring = true).onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Lämning och hämtning"), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription("Redigera") and hasClickAction()).performClick()
        // School is a series: first the question whether to change this time or the whole series.
        compose.waitUntilAtLeastOneExists(hasText("Hela serien"), timeoutMillis = 10_000)
        snap("event_edit_scope")
        tap("Hela serien")
        compose.waitUntilAtLeastOneExists(hasText("Spara") and hasClickAction(), timeoutMillis = 10_000)
        snap("event_editor")
    }

    @Test
    fun calendarFilter() {
        launch()
        openTab("Kalender")
        compose.waitUntilAtLeastOneExists(hasText("Vecka 41 · oktober"), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription("Filtrera") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Visa i kalendern"), timeoutMillis = 10_000)
        snap("calendar_filter")
    }

    @Test
    fun recipeDetails() {
        launch()
        openTab("Mat")
        compose.waitUntilAtLeastOneExists(hasText("Matsedel"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Recept").onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Pasta med pesto och broccoli"), timeoutMillis = 10_000)
        compose.onAllNodesWithText("Pasta med pesto och broccoli").onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ingredienser", substring = true), timeoutMillis = 10_000)
        snap("recipe_details")
    }

    @Test
    fun subscriptions() {
        launch()
        openMore("Inställningar")
        compose.waitUntilAtLeastOneExists(hasText("Prenumerationer", substring = true) and hasClickAction(), timeoutMillis = 10_000)
        compose.onNode(hasText("Prenumerationer", substring = true) and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Tillbaka"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("subscriptions")
    }

    /** "Sjuk idag?" on Idag: the sheet, then the card with what is cancelled and who stays home (ADR-047). */
    @Test
    fun todaySickChild() {
        launch()
        tap("Sjuk idag?")
        compose.waitUntilAtLeastOneExists(hasText("Vem är sjuk?"), timeoutMillis = 10_000)
        // The chip in the sheet, not one of Ella's rows on Idag behind it.
        compose.onNode(hasText("Ella") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        snap("today_sick_sheet")
        tap("Sjukanmäl")
        compose.waitUntilAtLeastOneExists(hasText("Ella är sjuk idag"), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText("Vem stannar hemma?"), timeoutMillis = 10_000)
        compose.waitForIdle()
        snap("today_sick_card")
    }

    private fun openMore(item: String) {
        compose.onNodeWithText("Mer").performClick()
        compose.waitUntilAtLeastOneExists(hasText(item) and hasClickAction(), timeoutMillis = 10_000)
        compose.onNode(hasText(item) and hasClickAction()).performClick()
        compose.waitForIdle()
    }
}

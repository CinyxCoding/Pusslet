package se.pusslet.android.screenshots

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config
import se.pusslet.data.repository.TaskRepository
import se.pusslet.feature.tasks.TasksActions
import se.pusslet.feature.tasks.TasksScreen
import se.pusslet.feature.tasks.TasksUiState
import se.pusslet.shell.PussletApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Vanliga sysslor" (ADR-065) in the real app with the demo family: from the list, from a new task, adding and "Ångra". */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ChoreCatalogScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun openTasks() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.onNodeWithText("Mer").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Uppgifter och sysslor"))
        compose.onNodeWithText("Uppgifter och sysslor").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription("Ny uppgift"), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    private fun openCatalog() {
        openTasks()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Vanliga sysslor"))
        compose.onNodeWithText("Vanliga sysslor").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Välj det ni brukar göra", substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun saved(): List<String> = runBlocking { GlobalContext.get().get<TaskRepository>().tasks.first().map { it.title } }

    @Test
    fun theListOpensItAndChoresCanBeAddedAndUndone() {
        openCatalog()
        compose.onRoot().captureRoboImage("src/test/snapshots/chore_catalog.png")
        val audits = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map(AccessibilityAudit::audit)
        assertTrue(audits.sumOf { it.checked } > 0)
        assertEquals(emptyList(), audits.flatMap { it.problems }, "Accessibility of Vanliga sysslor")
        compose.onNodeWithText("Lägg till").assertIsNotEnabled()

        scrollTo("Rengöra ugnen")
        compose.onNodeWithText("Rengöra ugnen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lägg till 1 syssla").assertIsEnabled()
        scrollTo("Rengöra kylskåpet")
        compose.onNodeWithText("Rengöra kylskåpet").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lägg till 2 sysslor").performClick()

        compose.waitUntilAtLeastOneExists(hasText("2 sysslor tillagda"), timeoutMillis = 15_000)
        assertTrue(saved().containsAll(listOf("Rengöra ugnen", "Rengöra kylskåpet")), "${saved()}")

        compose.onNodeWithText("Ångra").performClick()
        // Under Robolectric the main thread only runs when the test lets it: the view model's work needs waitForIdle.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.waitForIdle()
            "Rengöra ugnen" !in saved() && "Rengöra kylskåpet" !in saved()
        }
    }

    /** The demo family already has "Dammsuga": it shows as there, and cannot be picked a second time. */
    @Test
    fun whatTheFamilyHasIsNotOffered() {
        openCatalog()
        assertEquals(1, saved().count { it == "Dammsuga" })

        val row = compose.onNode(hasText("Dammsuga").and(hasText("Finns redan")))
        row.assertIsNotEnabled()
        row.assertIsOn()
        compose.onNodeWithText("Lägg till").assertIsNotEnabled()
    }

    /** From an untouched new task: back and adding both lead to the list, not to the empty form. */
    @Test
    fun aNewTaskOffersItAndBackLeadsToTheList() {
        openTasks()
        compose.onNodeWithContentDescription("Ny uppgift").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Välj vanliga sysslor"), timeoutMillis = 15_000)

        compose.onNodeWithText("Välj vanliga sysslor").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Välj det ni brukar göra", substring = true), timeoutMillis = 15_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Tillbaka").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Ny uppgift").assertExists()
        compose.onNodeWithText("Välj vanliga sysslor").assertDoesNotExist()
    }

    /** The button in the middle is enough: no row further down says the same. */
    @Test
    fun anEmptyListOffersItToAParent() {
        var opened = 0
        val parent = TasksUiState(loading = false, today = LocalDate(2026, 10, 20), canEdit = true)
        compose.setContent { TasksScreen(parent, actions = TasksActions(onOpenCatalog = { opened++ })) }

        compose.onNodeWithText("Inga uppgifter just nu").assertExists()
        compose.onNodeWithText("Välj vanliga sysslor").performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Vanliga sysslor").assertDoesNotExist()
    }

    @Test
    fun aCaregiverIsNotOfferedIt() {
        val helper = TasksUiState(loading = false, today = LocalDate(2026, 10, 20), canEdit = false)
        compose.setContent { TasksScreen(helper) }

        compose.onNodeWithText("Inga uppgifter just nu").assertExists()
        compose.onNodeWithText("Välj vanliga sysslor").assertDoesNotExist()
        compose.onNodeWithText("Vanliga sysslor").assertDoesNotExist()
    }
}

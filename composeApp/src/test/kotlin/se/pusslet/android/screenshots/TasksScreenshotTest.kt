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

/** To-dos, rotating chores and the workload view for the demo family. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class TasksScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun openTasks() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        compose.waitUntilAtLeastOneExists(hasText("Ella", substring = true), timeoutMillis = 15_000)
        compose.onNodeWithText("Mer").performClick()
        compose.onNodeWithText("Uppgifter och sysslor").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Mina"), timeoutMillis = 10_000)
        compose.waitForIdle()
    }

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    @Test
    fun mine() {
        openTasks()
        snap("tasks_mine")
    }

    @Test
    fun chores() {
        openTasks()
        compose.onNodeWithText("Sysslor").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Dammsuga"), timeoutMillis = 10_000)
        snap("tasks_chores")
    }

    @Test
    fun workload() {
        openTasks()
        compose.onNodeWithText("Fördelning").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Lämningar"), timeoutMillis = 10_000)
        snap("tasks_workload")
    }
}

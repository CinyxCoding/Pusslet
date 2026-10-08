package se.pusslet.android.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.PussletApp
import se.pusslet.ui.theme.ThemeMode

/** Phase 0 smoke test: the app shell renders the bottom navigation in light and dark mode, Swedish and English. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ShellScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shellLight() {
        compose.setContent { PussletApp(themeMode = ThemeMode.Light) }
        compose.onNodeWithText("Idag").assertExists()
        compose.onNodeWithText("Handla").assertExists()
        compose.onRoot().captureRoboImage("src/test/snapshots/shell_light.png")
    }

    @Test
    fun shellDark() {
        compose.setContent { PussletApp(themeMode = ThemeMode.Dark) }
        compose.onRoot().captureRoboImage("src/test/snapshots/shell_dark.png")
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun shellEnglish() {
        compose.setContent { PussletApp(themeMode = ThemeMode.Light) }
        compose.onNodeWithText("Today").assertExists()
        compose.onRoot().captureRoboImage("src/test/snapshots/shell_light_en.png")
    }
}

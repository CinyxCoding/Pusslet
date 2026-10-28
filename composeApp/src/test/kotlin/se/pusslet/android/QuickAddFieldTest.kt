package se.pusslet.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.shell.quickadd.QuickAddContent
import se.pusslet.shell.quickadd.QuickAddUiState
import kotlin.test.assertEquals

/**
 * The quick-add field when the view model's text arrives late, as it does through collectAsStateWithLifecycle: what is
 * typed (å, ä and ö included) must stay, and only text set from outside may replace it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class QuickAddFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private var vmText by mutableStateOf("")
    private val sent = mutableListOf<String>()

    private fun field() = compose.onNode(hasSetTextAction())

    private fun show() = compose.setContent {
        QuickAddContent(
            state = QuickAddUiState(text = vmText),
            onTextChange = { sent += it },
            onToggleEditing = {},
            onConvert = { _, _ -> },
            onTitle = { _, _ -> },
            onDate = { _, _ -> },
            onStart = { _, _ -> },
            onEnd = { _, _ -> },
            onTogglePerson = { _, _ -> },
            onRemove = {},
            onSave = {},
            onClose = {},
        )
    }

    @Test
    fun typedTextStaysWhileTheViewModelLagsBehind() {
        show()
        field().performTextInput("Köp ")
        field().assertTextEquals("Köp ")
        field().performTextInput("smör")
        // The view model only now echoes the first change: an older text must not replace what is typed.
        vmText = "Köp "
        compose.waitForIdle()
        field().assertTextEquals("Köp smör")
        vmText = "Köp smör"
        compose.waitForIdle()
        field().assertTextEquals("Köp smör")
        assertEquals(listOf("Köp ", "Köp smör"), sent)
    }

    @Test
    fun goingBackToTheLaggingTextIsStillSent() {
        show()
        field().performTextInput("Å")
        field().performTextInput("sa")
        field().performTextReplacement("Å")
        assertEquals(listOf("Å", "Åsa", "Å"), sent)
    }

    @Test
    fun textFromOutsideReplacesTheField() {
        show()
        field().performTextInput("Ärtsoppa")
        vmText = "Ärtsoppa"
        compose.waitForIdle()
        // An example chip or shared text.
        vmText = "Märta kalas lör 14-16"
        compose.waitForIdle()
        field().assertTextEquals("Märta kalas lör 14-16")
    }
}

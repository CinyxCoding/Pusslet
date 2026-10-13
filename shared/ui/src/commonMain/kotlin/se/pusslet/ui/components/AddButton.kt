package se.pusslet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the round + does on the screen that is showing (ADR-023): one add entry per screen. The shell draws the button and
 * provides this state through [LocalAddButton]; each tab root says what + means there with [RegisterAddButton].
 */
@Stable
class AddButtonState {
    internal class Registration(val label: String, val onClick: () -> Unit)

    internal var current: Registration? by mutableStateOf(null)

    /** TalkBack label of the + ("Lägg till vara"), or null when the screen has no +. */
    val label: String? get() = current?.label

    fun click() {
        current?.onClick?.invoke()
    }
}

val LocalAddButton = staticCompositionLocalOf<AddButtonState?> { null }

/**
 * Shows the round + while this composable is on screen. [label] is the TalkBack name ("Planera middag"); [onClick] always
 * sees the latest lambda. Does nothing where no shell provides [LocalAddButton] (previews, screenshot tests of one screen).
 */
@Composable
fun RegisterAddButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val state = LocalAddButton.current ?: return
    val latest by rememberUpdatedState(onClick)
    DisposableEffect(state, label, enabled) {
        val registration = AddButtonState.Registration(label) { latest() }
        if (enabled) state.current = registration
        // Identity check: during a tab switch the next screen may register before this one leaves.
        onDispose { if (state.current === registration) state.current = null }
    }
}

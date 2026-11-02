package se.pusslet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler

/**
 * The system Back (and the predictive back gesture) asks before unsaved changes are lost: while [enabled], Back calls
 * [onBack] – typically "Släng ändringarna?" – instead of leaving the editor. The non-deprecated `NavigationBackHandler`
 * lives in navigationevent-compose, which the UI modules do not depend on directly; this handler is built on the same
 * dispatcher, so it takes precedence over NavDisplay's own back while [enabled].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun UnsavedChangesBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * The system Back steps back inside a screen made of steps, such as the pages of the guide (ADR-062): while [enabled],
 * Back calls [onBack] instead of leaving. Built like [UnsavedChangesBackHandler], so it also comes before NavDisplay's
 * own back, and it works where there is no NavDisplay at all (the guide that opens before the shell).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun StepBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

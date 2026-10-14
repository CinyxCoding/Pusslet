package se.pusslet.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Shows a receipt with Ångra ("Mjölk bockad · Ångra", "6 varor rensade · Ångra") and returns true when the user pressed undo.
 *
 * Use it instead of "Är du säker?" for everyday changes, and only when undo really restores the previous state. [message]
 * says what happened (no "!"), [undoLabel] is normally `action_undo`. A snackbar that is already showing is dismissed first,
 * so the newest change is the one that can be undone; launch one coroutine per receipt so a newer one can replace it.
 */
suspend fun SnackbarHostState.showUndo(
    message: String,
    undoLabel: String,
    duration: SnackbarDuration = SnackbarDuration.Long,
): Boolean {
    currentSnackbarData?.dismiss()
    return showSnackbar(message = message, actionLabel = undoLabel, duration = duration) == SnackbarResult.ActionPerformed
}

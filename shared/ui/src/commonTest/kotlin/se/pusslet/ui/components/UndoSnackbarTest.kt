package se.pusslet.ui.components

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoSnackbarTest {
    @Test
    fun undoPressedReturnsTrue() = runTest {
        val host = SnackbarHostState()
        val result = async { host.showUndo("Mjölk bockad", "Ångra") }
        runCurrent()
        val shown = assertNotNull(host.currentSnackbarData)
        assertEquals("Mjölk bockad", shown.visuals.message)
        assertEquals("Ångra", shown.visuals.actionLabel)
        shown.performAction()
        assertTrue(result.await())
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun dismissedReturnsFalse() = runTest {
        val host = SnackbarHostState()
        val result = async { host.showUndo("6 varor rensade", "Ångra") }
        runCurrent()
        assertNotNull(host.currentSnackbarData).dismiss()
        assertFalse(result.await())
    }

    @Test
    fun aNewReceiptReplacesTheOneShowing() = runTest {
        val host = SnackbarHostState()
        val first = async { host.showUndo("Mjölk bockad", "Ångra") }
        runCurrent()
        val second = async { host.showUndo("Bröd bockad", "Ångra") }
        runCurrent()
        assertFalse(first.await())
        val shown = assertNotNull(host.currentSnackbarData)
        assertEquals("Bröd bockad", shown.visuals.message)
        shown.performAction()
        assertTrue(second.await())
    }
}

package se.pusslet.data.di

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundScopeTest {
    @Test
    fun aFailingJobIsLoggedAndTheOthersKeepRunning() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = backgroundScope(StandardTestDispatcher(testScheduler)) { failures += it }
        var later = false

        scope.launch { error("a bug") }
        testScheduler.advanceUntilIdle()
        scope.launch { later = true }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a bug"), failures.map { it.message })
        assertTrue(later, "the scope still runs new jobs")
        assertTrue(scope.isActive)
    }
}

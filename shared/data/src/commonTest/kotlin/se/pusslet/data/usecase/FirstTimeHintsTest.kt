package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.repository.PreferencesRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstTimeHintsTest {
    private val dao = FakePreferenceDao()

    @Test
    fun aDismissedHintStaysGoneAfterARestart() = runTest {
        val hints = FirstTimeHints(PreferencesRepository(dao))
        assertTrue(hints.shouldShow(HintIds.TodayAdd).first())
        hints.dismiss(HintIds.TodayAdd)
        assertFalse(hints.shouldShow(HintIds.TodayAdd).first())
        assertTrue(hints.shouldShow(HintIds.ShoppingMerge).first())

        // A new repository on the same storage is what the next start of the app sees.
        val restarted = FirstTimeHints(PreferencesRepository(dao))
        assertFalse(restarted.shouldShow(HintIds.TodayAdd).first())
    }

    @Test
    fun dismissAllHidesEveryHint() = runTest {
        val hints = FirstTimeHints(PreferencesRepository(dao))
        hints.dismissAll()
        HintIds.all.forEach { assertFalse(hints.shouldShow(it).first()) }
    }
}

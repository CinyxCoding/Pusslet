package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.repository.GuideStatus
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the guide through the app opens by itself (ADR-062). */
class AppGuideTest {
    private val dao = FakePreferenceDao()

    @Test
    fun aPhoneThatUsedTheAppBeforeNeverGetsItByItself() = runTest {
        val guide = AppGuide(PreferencesRepository(dao))
        assertFalse(guide.due.first())
    }

    @Test
    fun theFirstStartMakesItDueUntilItIsDone() = runTest {
        val guide = AppGuide(PreferencesRepository(dao))
        guide.firstStartDone()
        assertTrue(guide.due.first())

        // A new repository on the same storage is what the next start of the app sees.
        assertTrue(AppGuide(PreferencesRepository(dao)).due.first())

        guide.done()
        assertFalse(AppGuide(PreferencesRepository(dao)).due.first())
    }

    @Test
    fun aGuideAlreadyDoneIsNotDueAgainAfterAnotherFirstStart() = runTest {
        val prefs = PreferencesRepository(dao)
        val guide = AppGuide(prefs)
        guide.done()

        // "Börja på riktigt" after the example family, or a family joined later: this phone has been through it.
        guide.firstStartDone()

        assertFalse(guide.due.first())
        assertEquals(GuideStatus.Done, prefs.get(Prefs.Guide))
    }
}

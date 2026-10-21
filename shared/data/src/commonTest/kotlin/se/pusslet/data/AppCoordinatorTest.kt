package se.pusslet.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppCoordinatorTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-13T16:00:00Z")
    }

    /** What survives an app restart on this phone: the preferences table and the local documents. */
    private val prefDao = FakePreferenceDao()
    private val local = InMemorySyncLocalStore()

    private val demo = BackendConfig(BackendMode.LocalOnly, seedDemoData = true, versionName = "test")
    private val prod = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test")

    /** One app process: everything in memory is new, the stored data is shared. */
    private inner class AppProcess(scope: CoroutineScope, config: BackendConfig, work: List<AppProcessWork> = emptyList()) {
        val sessions = SessionRepository(prefDao)
        val prefs = PreferencesRepository(prefDao)
        val docs = DocumentStore(local, sessions, clock)
        val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), SequentialIdGenerator(), clock)
        val coordinator = AppCoordinator(
            scope = scope,
            config = config,
            sessions = sessions,
            auth = LocalOnlyAuthRepository(),
            familyRemote = LocalOnlyFamilyRemote,
            docs = docs,
            engine = SyncEngine(local, InMemoryRemoteStore(), docs),
            seed = DemoSeed(family, docs, clock),
            prefs = prefs,
            zone = zone,
            clock = clock,
            processWork = work,
        )
    }

    @Test
    fun developerToolsOnlyInBuildsWithDemoData() {
        assertTrue(demo.developerTools)
        assertFalse(prod.developerTools)
    }

    @Test
    fun replayingFirstStartClearsTheDemoAndStaysOnWelcome() = runTest {
        val app = AppProcess(backgroundScope, demo)
        app.coordinator.start()
        runCurrent()
        val familyId = assertNotNull(app.sessions.session.value.familyId)
        assertTrue(app.prefs.get(Prefs.OnboardingDone))
        assertTrue(app.coordinator.isDemo.first())

        assertTrue(app.coordinator.replayOnboarding(local))

        assertFalse(app.sessions.session.value.hasFamily)
        assertFalse(app.prefs.get(Prefs.OnboardingDone))
        assertFalse(app.coordinator.isDemo.first())
        assertEquals(emptyList(), local.listFamily(familyId))

        // A restart does not seed the example family again, so the welcome screen is still there.
        val restarted = AppProcess(backgroundScope, demo)
        restarted.coordinator.start()
        runCurrent()
        assertTrue(restarted.coordinator.ready.value)
        assertFalse(restarted.sessions.session.value.hasFamily)
        assertFalse(restarted.prefs.get(Prefs.OnboardingDone))
    }

    @Test
    fun processWorkStartsOnceTheSessionIsRead() = runTest {
        val familyAtStart = ArrayList<String?>()
        lateinit var app: AppProcess
        app = AppProcess(backgroundScope, demo, listOf(AppProcessWork { familyAtStart += app.sessions.session.value.familyId }))

        app.coordinator.start()
        runCurrent()

        // Once, after the demo family was seeded: what the work watches (e.g. subscriptions) belongs to the family here.
        assertNotNull(familyAtStart.single())
        assertEquals(app.sessions.session.value.familyId, familyAtStart.single())
    }

    @Test
    fun productionNeverReplaysFirstStart() = runTest {
        val app = AppProcess(backgroundScope, prod)
        app.sessions.load()
        app.family.createFamily("Familjen Test", "Sara")
        app.prefs.set(Prefs.OnboardingDone, true)

        assertFalse(app.coordinator.replayOnboarding(local))

        assertTrue(app.sessions.session.value.hasFamily)
        assertTrue(app.prefs.get(Prefs.OnboardingDone))
        assertFalse(app.prefs.get(Prefs.SkipDemoSeed))
    }

    /** "Utforska med en exempelfamilj" is on every welcome screen, so "Börja på riktigt" (UX plan 4.8) works in production too. */
    @Test
    fun theExampleFamilyCanBeLeftInProduction() = runTest {
        val app = AppProcess(backgroundScope, prod)
        app.sessions.load()
        val familyId = app.family.createFamily("Familjen Lind", "Sara", demo = true).id
        app.prefs.set(Prefs.OnboardingDone, true)

        assertTrue(app.coordinator.replayOnboarding(local))

        assertFalse(app.sessions.session.value.hasFamily)
        assertFalse(app.prefs.get(Prefs.OnboardingDone))
        assertEquals(emptyList(), local.listFamily(familyId))
    }
}

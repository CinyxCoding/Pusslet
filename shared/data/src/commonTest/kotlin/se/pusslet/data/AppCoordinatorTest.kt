package se.pusslet.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.remote.AccountFamily
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.AuthUser
import se.pusslet.data.remote.FamilyRemote
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.GuideStatus
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.usecase.AppGuide
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
    private inner class AppProcess(
        scope: CoroutineScope,
        config: BackendConfig,
        work: List<AppProcessWork> = emptyList(),
        remote: FamilyRemote = LocalOnlyFamilyRemote,
        auth: AuthRepository = LocalOnlyAuthRepository(),
    ) {
        val sessions = SessionRepository(prefDao)
        val prefs = PreferencesRepository(prefDao)
        val docs = DocumentStore(local, sessions, clock)
        val family = FamilyRepository(docs, sessions, remote, auth, SequentialIdGenerator(), clock)
        val coordinator = AppCoordinator(
            scope = scope,
            config = config,
            sessions = sessions,
            auth = auth,
            familyRemote = remote,
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

    /** The demo build skips the welcome screen, so its first start is over once the example family is seeded (ADR-062). */
    @Test
    fun theDemoBuildsFirstStartOpensTheGuide() = runTest {
        val app = AppProcess(backgroundScope, demo)
        app.coordinator.start()
        runCurrent()
        assertTrue(app.coordinator.ready.value)
        assertTrue(AppGuide(app.prefs).due.first())
    }

    @Test
    fun aGuideAlreadyDoneStaysDoneWhenTheExampleFamilyIsSeeded() = runTest {
        val app = AppProcess(backgroundScope, demo)
        app.prefs.set(Prefs.Guide, GuideStatus.Done)
        app.coordinator.start()
        runCurrent()
        assertTrue(app.sessions.session.value.hasFamily)
        assertFalse(AppGuide(app.prefs).due.first())
    }

    /** Without the example family the welcome screen comes first; onboarding makes the guide due, not the start. */
    @Test
    fun aProductionStartLeavesTheGuideToOnboarding() = runTest {
        val app = AppProcess(backgroundScope, prod)
        app.coordinator.start()
        runCurrent()
        assertFalse(AppGuide(app.prefs).due.first())
    }

    /** A family brought back after sign-in on a new phone is on the server already, so it is never created again (ADR-063). */
    @Test
    fun aFamilyBroughtBackIsNotCreatedAgainOnTheServer() = runTest {
        val server = RecordingRemote()
        val app = AppProcess(backgroundScope, prod, remote = server, auth = SignedIn("uid-anna"))
        app.coordinator.start()
        runCurrent()

        app.family.restore(AccountFamily("f-berg", "Familjen Berg", "m-anna", Role.Parent))
        runCurrent()

        assertEquals(Session(familyId = "f-berg", memberId = "m-anna", uid = "uid-anna", role = Role.Parent), app.sessions.session.value)
        assertEquals(emptyList(), server.bootstrapped, "An empty name must never be written over the family's own")
        assertEquals(listOf("uid-anna" to "f-berg"), server.remembered)
    }

    @Test
    fun aFamilyMadeOnThisPhoneIsCreatedOnTheServerWithItsName() = runTest {
        val server = RecordingRemote()
        val app = AppProcess(backgroundScope, prod, remote = server, auth = SignedIn("uid-sara"))
        app.coordinator.start()
        runCurrent()

        val family = app.family.createFamily("Familjen Lind", "Sara")
        runCurrent()

        assertEquals(listOf(family.id to "Familjen Lind"), server.bootstrapped)
    }

    /** A server that remembers what the coordinator asked of it. */
    private class RecordingRemote : FamilyRemote by LocalOnlyFamilyRemote {
        override val available = true
        val bootstrapped = mutableListOf<Pair<String, String>>()
        val remembered = mutableListOf<Pair<String, String>>()

        override suspend fun bootstrapFamily(
            familyId: String,
            name: String,
            memberId: String,
            uid: String,
            shared: Boolean,
            home: String?,
        ) {
            bootstrapped += familyId to name
        }

        override suspend fun rememberFamily(uid: String, familyId: String) {
            remembered += uid to familyId
        }
    }

    private class SignedIn(uid: String) : AuthRepository by LocalOnlyAuthRepository() {
        override val available = true
        override val currentUser: StateFlow<AuthUser?> = MutableStateFlow(AuthUser(uid, "$uid@example.se", null, isAnonymous = false))
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

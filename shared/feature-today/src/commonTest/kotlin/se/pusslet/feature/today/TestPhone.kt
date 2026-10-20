package se.pusslet.feature.today

import kotlinx.coroutines.CoroutineScope
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.SyncEngine
import se.pusslet.data.usecase.FirstTimeHints

/** A production build: no developer tools, no demo seeding. */
val ProductionBuild = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test")

/** The demo flavor: developer tools on. */
val DemoBuild = BackendConfig(BackendMode.LocalOnly, seedDemoData = true, versionName = "test")

/** A backend is configured, so invites can be sent ("Bjud in Johan" can tick itself off). */
val InvitesAvailable: AuthRepository = object : AuthRepository by LocalOnlyAuthRepository() {
    override val available = true
}

/**
 * The phone around a [TestWorld]: the real [AppCoordinator] over the world's documents and preferences, with the stored
 * session it acts on ([sessions], set with [store]). The view model itself reads [TestWorld.sessions].
 */
class TestPhone(val world: TestWorld, scope: CoroutineScope, config: BackendConfig = ProductionBuild) {
    val sessions = SessionRepository(FakePreferenceDao())
    val coordinator = AppCoordinator(
        scope = scope,
        config = config,
        sessions = sessions,
        auth = LocalOnlyAuthRepository(),
        familyRemote = LocalOnlyFamilyRemote,
        docs = world.docs,
        engine = SyncEngine(world.local, InMemoryRemoteStore(), world.docs),
        seed = DemoSeed(world.family, world.docs, world.clock),
        prefs = world.prefs,
        zone = world.zone,
        clock = world.clock,
    )

    /** The phone's stored session; by default the same as the world's. */
    suspend fun store(session: Session = world.sessions.session.value): TestPhone {
        sessions.update { session }
        return this
    }

    fun todayViewModel(auth: AuthRepository = LocalOnlyAuthRepository()) = TodayViewModel(
        world.family,
        world.calendar,
        world.packing,
        world.meals,
        world.tasks,
        world.lunches,
        world.shopping,
        world.prefs,
        NoWeather,
        world.sessions,
        world.handoffs,
        FirstTimeHints(world.prefs),
        coordinator,
        world.local,
        auth,
        world.ids,
        world.clock,
        world.zone,
    )
}

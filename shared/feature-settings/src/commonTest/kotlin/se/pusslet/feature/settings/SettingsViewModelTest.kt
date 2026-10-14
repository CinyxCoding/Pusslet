package se.pusslet.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheSummary
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionRepository
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemoryRemoteStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.sync.SyncEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakePreferenceDao : PreferenceDao {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun observe(key: String): Flow<String?> = values.map { it[key] }

    override suspend fun get(key: String): String? = values.value[key]

    override suspend fun put(entity: PreferenceEntity) {
        values.value = values.value + (entity.key to entity.value)
    }

    override suspend fun remove(key: String) {
        values.value = values.value - key
    }

    override suspend fun clear() {
        values.value = emptyMap()
    }
}

private class FakeCacheDao : CacheDao {
    private val entries = MutableStateFlow<Map<String, CacheEntryEntity>>(emptyMap())

    override suspend fun get(key: String) = entries.value[key]

    override fun observe(key: String): Flow<CacheEntryEntity?> = entries.map { it[key] }

    override suspend fun put(entity: CacheEntryEntity) {
        entries.value = entries.value + (entity.key to entity)
    }

    override suspend fun setError(key: String, error: String?) = Unit

    override suspend fun deletePrefix(prefix: String) {
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
    }

    override suspend fun summaries() = entries.value.values.map { CacheSummary(it.key, it.fetchedAt, it.lastError) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val demo = BackendConfig(BackendMode.LocalOnly, seedDemoData = true, versionName = "test")
    private val prod = BackendConfig(BackendMode.LocalOnly, seedDemoData = false, versionName = "test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** One phone; [withFamily] adds a family where this device's user has the given role. */
    private class World(scope: CoroutineScope, val config: BackendConfig) {
        val clock = object : Clock {
            override fun now() = Instant.parse("2026-10-13T16:00:00Z")
        }
        val zone = TimeZone.of("Europe/Stockholm")
        val prefDao = FakePreferenceDao()
        val local = InMemorySyncLocalStore()
        val sessions = SessionRepository(prefDao)
        val prefs = PreferencesRepository(prefDao)
        val docs = DocumentStore(local, sessions, clock)
        val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), SequentialIdGenerator(), clock)
        val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
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
        )

        suspend fun withFamily(role: Role): World {
            family.createFamily("Familjen Test", "Sara", demo = config.seedDemoData)
            sessions.update { it.copy(role = role) }
            prefs.set(Prefs.OnboardingDone, true)
            return this
        }
    }

    private fun TestScope.viewModel(world: World): SettingsViewModel {
        val vm = SettingsViewModel(
            world.prefs,
            world.family,
            NoDeviceCalendars,
            world.calendar,
            world.config,
            world.sessions,
            world.coordinator,
            world.local,
        )
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun caregiversDoNotGetPlaceOrCalendarExport() = runTest {
        val caregiver = viewModel(World(backgroundScope, prod).withFamily(Role.Caregiver))
        assertFalse(caregiver.state.value.canEditFamily)

        val parent = viewModel(World(backgroundScope, prod).withFamily(Role.Parent))
        assertTrue(parent.state.value.canEditFamily)
    }

    @Test
    fun developerRowOnlyInDemoAndDevBuilds() = runTest {
        assertTrue(viewModel(World(backgroundScope, demo).withFamily(Role.Parent)).state.value.developerTools)
        assertFalse(viewModel(World(backgroundScope, prod).withFamily(Role.Parent)).state.value.developerTools)
    }

    @Test
    fun replayFirstStartLeavesTheExampleFamily() = runTest {
        val world = World(backgroundScope, demo).withFamily(Role.Parent)
        val vm = viewModel(world)

        vm.replayOnboarding()

        assertFalse(world.sessions.session.value.hasFamily)
        assertFalse(world.prefs.get(Prefs.OnboardingDone))
        assertFalse(vm.state.value.replayFailed)
    }

    @Test
    fun replayIsRefusedInProduction() = runTest {
        val world = World(backgroundScope, prod).withFamily(Role.Parent)
        val vm = viewModel(world)

        vm.replayOnboarding()

        assertTrue(world.sessions.session.value.hasFamily)
        assertTrue(vm.state.value.replayFailed)
    }
}

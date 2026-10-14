package se.pusslet.feature.today

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.Place
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.core.weather.Forecast
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheSummary
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sources.Sourced
import se.pusslet.data.sources.WeatherSource
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.HandoffAssigner
import kotlin.time.Clock
import kotlin.time.Instant

class FakePreferenceDao : PreferenceDao {
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

class FakeCacheDao : CacheDao {
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

object NoPurchases : PurchaseLogDao {
    override suspend fun insert(entity: PurchaseLogEntity) = Unit

    override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

    override suspend fun deleteFamily(familyId: String) = Unit
}

object NoLunchFeeds : LunchMenuProvider {
    override fun supports(source: LunchSource) = false

    override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? = null
}

/** SMHI is offline in tests: the weather row says "Inget väder just nu" (or "Välj ort" without a place). */
object NoWeather : WeatherSource {
    override suspend fun forecast(place: Place, force: Boolean): Sourced<Forecast>? = null
}

/** Real repositories over the in-memory document store, the way the app wires them, for view model tests. */
class TestWorld(role: Role = Role.Parent, now: Instant = Instant.parse("2026-10-13T06:00:00Z")) {
    val clock = object : Clock {
        override fun now() = now
    }
    val zone = TimeZone.of("Europe/Stockholm")
    val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = role))
    val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    val ids = SequentialIdGenerator()
    val prefs = PreferencesRepository(FakePreferenceDao())
    val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
    val packing = PackingRepository(docs)
    val meals = MealRepository(docs)
    val tasks = TaskRepository(docs)
    val lunches = LunchRepository(docs, NoLunchFeeds, family)
    val shopping = ShoppingRepository(docs, NoPurchases, sessions, clock)
    val handoffs = HandoffAssigner(calendar)
}

package se.pusslet.feature.family

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
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

/** Real repositories over the in-memory document store, before any family exists (first start). */
class TestWorld(now: Instant = Instant.parse("2026-10-13T16:00:00Z")) {
    val clock = object : Clock {
        override fun now() = now
    }
    val zone = TimeZone.of("Europe/Stockholm")
    val sessions = FixedSessionProvider(Session())
    val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    val ids = SequentialIdGenerator()
    val prefs = PreferencesRepository(FakePreferenceDao())
    val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    val seed = DemoSeed(family, docs, clock)
}

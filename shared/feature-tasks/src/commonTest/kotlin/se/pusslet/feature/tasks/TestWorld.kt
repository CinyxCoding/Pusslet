package se.pusslet.feature.tasks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
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
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.feature.tasks.distribution.DistributionViewModel
import se.pusslet.feature.tasks.editor.TaskEditorViewModel
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

/**
 * Real repositories over the in-memory document store, the way the app wires them, for view model tests. "Today" is
 * Tuesday 20 October 2026 (week 43), 08:00 in Stockholm. The viewer is Sara unless [memberId] says otherwise (morfar Bengt
 * is "m-bengt" with [Role.Caregiver]).
 */
class TestWorld(
    role: Role = Role.Parent,
    now: Instant = Instant.parse("2026-10-20T06:00:00Z"),
    memberId: String = SARA,
) {
    val clock = object : Clock {
        override fun now() = now
    }
    val zone = TimeZone.of("Europe/Stockholm")
    val sessions = FixedSessionProvider(Session(familyId = "f", memberId = memberId, uid = "u", role = role))
    val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    val ids = SequentialIdGenerator()
    val prefs = PreferencesRepository(FakePreferenceDao())
    val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
    val tasks = TaskRepository(docs)
    val deleted = DeletedTasks()

    fun list() = TasksViewModel(tasks, family, sessions, deleted, clock, zone)

    fun editor() = TaskEditorViewModel(tasks, family, sessions, deleted, clock, zone, ids)

    fun distribution() = DistributionViewModel(tasks, family, calendar, sessions, clock, zone)

    /** Sara and Johan (parents), morfar Bengt (caregiver) and Ella (9). */
    suspend fun lind() {
        family.saveMember(Member(SARA, "Sara", Role.Parent, MemberColor.Sage))
        family.saveMember(Member(JOHAN, "Johan", Role.Parent, MemberColor.Ocean))
        family.saveMember(Member(BENGT, "Bengt", Role.Caregiver, MemberColor.Slate))
        family.saveChild(Child(ELLA, "Ella"))
    }

    companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
    }
}

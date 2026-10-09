package se.pusslet.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheSummary
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.repository.AttachmentFiles
import se.pusslet.data.sources.FoodDatabase
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sources.Sourced
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A clock tests can move forward. */
class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}

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

    override suspend fun setError(key: String, error: String?) {
        entries.value[key]?.let { put(it.copy(lastError = error)) }
    }

    override suspend fun deletePrefix(prefix: String) {
        entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
    }

    override suspend fun summaries() = entries.value.values.map { CacheSummary(it.key, it.fetchedAt, it.lastError) }
}

class FakePurchaseLogDao : PurchaseLogDao {
    val entries = mutableListOf<PurchaseLogEntity>()

    override suspend fun insert(entity: PurchaseLogEntity) {
        entries += entity.copy(id = entries.size + 1L)
    }

    override suspend fun since(familyId: String, since: Long) =
        entries.filter { it.familyId == familyId && it.boughtAt >= since }.sortedByDescending { it.boughtAt }

    override suspend fun deleteFamily(familyId: String) {
        entries.removeAll { it.familyId == familyId }
    }
}

/** School lunches from a "feed": set [lunches] per reference. */
class FakeLunchProvider : LunchMenuProvider {
    val lunches = mutableMapOf<String, List<SchoolLunch>>()

    override fun supports(source: LunchSource) = true

    override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? =
        lunches[source.reference]?.let { Sourced(it, Instant.fromEpochMilliseconds(0), stale = false) }
}

/** Livsmedelsdatabasen without network: [online] false behaves like a phone that never downloaded it. */
class FakeFoodDatabase(
    val foods: MutableList<Food> = mutableListOf(),
    val values: MutableMap<Int, NutrientValues> = mutableMapOf(),
    var online: Boolean = true,
) : FoodDatabase {
    override suspend fun ensureLoaded(): Boolean = online

    override suspend fun search(query: String, limit: Int): List<Food> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return foods.filter { f -> words.any { w -> f.name.lowercase().contains(w) } }.take(limit)
    }

    override suspend fun food(number: Int): Food? = foods.firstOrNull { it.number == number }

    override suspend fun nutrients(number: Int): NutrientValues? = values[number]
}

class FakeProductLookup(val products: MutableMap<String, ProductInfo> = mutableMapOf()) : ProductLookup {
    val asked = mutableListOf<String>()

    override suspend fun product(barcode: String): ProductInfo? {
        asked += barcode
        return products[barcode]
    }
}

/** Pretend attachment folder: file name -> last modified. */
class FakeAttachmentFiles(private val clock: Clock) : AttachmentFiles {
    val stored = mutableMapOf<String, Instant>()
    val deleted = mutableListOf<String>()

    /** A picture "copied in" by the platform. */
    fun add(fileName: String, modified: Instant = clock.now()) {
        stored[fileName] = modified
    }

    override suspend fun delete(fileName: String) {
        stored.remove(fileName)
        deleted += fileName
    }

    override suspend fun deleteAll() {
        deleted += stored.keys
        stored.clear()
    }

    override suspend fun list(): Map<String, Instant> = stored.toMap()
}

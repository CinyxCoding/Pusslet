package se.pusslet.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheKeyDao
import se.pusslet.data.db.CacheSummary
import se.pusslet.data.db.FoodDao
import se.pusslet.data.db.FoodEntity
import se.pusslet.data.db.FoodNutrientsEntity
import se.pusslet.data.db.FoodVersion
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity

class FakeCacheDao : CacheDao {
    val entries = MutableStateFlow<Map<String, CacheEntryEntity>>(emptyMap())

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

/** The exact-key delete of [se.pusslet.data.db.CacheKeyDao] over a [FakeCacheDao]'s entries. */
class FakeCacheKeyDao(private val cache: FakeCacheDao) : CacheKeyDao {
    val deleted = ArrayList<List<String>>()

    override suspend fun delete(keys: List<String>) {
        deleted += keys
        cache.entries.value = cache.entries.value - keys.toSet()
    }
}

class FakeFoodDao : FoodDao {
    val foods = LinkedHashMap<Int, FoodEntity>()
    val nutrients = HashMap<Int, FoodNutrientsEntity>()

    override suspend fun count() = foods.size

    override suspend fun get(number: Int) = foods[number]

    override suspend fun versions() = foods.values.map { FoodVersion(it.number, it.version) }

    override suspend fun upsertAll(foods: List<FoodEntity>) = foods.forEach { this.foods[it.number] = it }

    /** Mimics the FTS prefix query ("mjölk* 3*"): every term must prefix-match a word. */
    override suspend fun search(query: String, limit: Int): List<FoodEntity> {
        val terms = query.split(' ').map { it.removeSuffix("*") }
        return foods.values.filter { f ->
            val words = f.name.lowercase().split(Regex("""[^\p{L}\d]+"""))
            terms.all { t -> words.any { it.startsWith(t) } }
        }.take(limit)
    }

    override suspend fun nutrients(number: Int) = nutrients[number]

    override suspend fun putNutrients(entity: FoodNutrientsEntity) {
        nutrients[entity.number] = entity
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

/** HttpClient backed by a request handler; records every request URL. */
class MockHttp(private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = ArrayList<String>()
    val client = HttpClient(
        MockEngine { request ->
            requests += request.url.toString()
            handler(request)
        },
    )

    companion object {
        fun MockRequestHandleScope.ok(body: String, contentType: String = "application/json") =
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", contentType))
    }
}

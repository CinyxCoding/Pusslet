package se.pusslet.data.sources

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutrientValues
import se.pusslet.data.db.FoodDao
import se.pusslet.data.db.FoodEntity
import se.pusslet.data.db.FoodNutrientsEntity
import se.pusslet.data.net.NetworkJson
import kotlin.time.Clock

interface FoodDatabase {
    /** Downloads/refreshes the food list when needed. Returns false when offline and nothing is cached yet. */
    suspend fun ensureLoaded(): Boolean

    suspend fun search(query: String, limit: Int = 20): List<Food>

    suspend fun food(number: Int): Food?

    /** Per 100 g. Fetched lazily and cached until the food's version changes. */
    suspend fun nutrients(number: Int): NutrientValues?
}

/**
 * Livsmedelsverkets Livsmedelsdatabas (CC BY 4.0). The API cannot search by name, so the whole list (~2 600 foods)
 * is stored locally with a full-text index; it is only rewritten where the `version` field changed.
 */
class LivsmedelsverketFoodDatabase(
    private val http: HttpClient,
    private val dao: FoodDao,
    private val clock: Clock = Clock.System,
    private val json: Json = NetworkJson,
) : FoodDatabase {
    private val log = Logger.withTag("Livsmedel")

    override suspend fun ensureLoaded(): Boolean {
        if (dao.count() > 0) return true
        return refresh()
    }

    /** Fetches the list and upserts rows whose version changed. */
    suspend fun refresh(): Boolean = try {
        val response = http.get("$BASE/livsmedel?offset=0&limit=$PAGE&sprak=1")
        if (!response.status.isSuccess()) throw SourceException("HTTP ${response.status.value}")
        val list = parseList(response.bodyAsText())
        val known = dao.versions().associate { it.number to it.version }
        val changed = list.filter { known[it.number] != it.version }
        if (changed.isNotEmpty()) dao.upsertAll(changed)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w { "Food list refresh failed: ${e::class.simpleName}" }
        dao.count() > 0
    }

    override suspend fun search(query: String, limit: Int): List<Food> {
        val fts = ftsQuery(query) ?: return emptyList()
        return runCatching { dao.search(fts, limit) }.getOrDefault(emptyList()).map { Food(it.number, it.name) }
    }

    override suspend fun food(number: Int): Food? = dao.get(number)?.let { Food(it.number, it.name) }

    override suspend fun nutrients(number: Int): NutrientValues? {
        val food = dao.get(number) ?: return null
        dao.nutrients(number)?.takeIf { it.version == food.version }?.let { cached ->
            return runCatching { json.decodeFromString(NutrientValues.serializer(), cached.json) }.getOrNull()
        }
        return try {
            val response = http.get("$BASE/livsmedel/$number/naringsvarden?sprak=1")
            if (!response.status.isSuccess()) return null
            val values = parseNutrients(response.bodyAsText())
            dao.putNutrients(
                FoodNutrientsEntity(
                    number,
                    food.version,
                    json.encodeToString(NutrientValues.serializer(), values),
                    clock.now().toEpochMilliseconds(),
                ),
            )
            values
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Nutrients fetch failed: ${e::class.simpleName}" }
            dao.nutrients(number)?.let { runCatching { json.decodeFromString(NutrientValues.serializer(), it.json) }.getOrNull() }
        }
    }

    fun parseList(body: String): List<FoodEntity> = json.decodeFromString(ListResponse.serializer(), body).livsmedel.map {
        FoodEntity(number = it.nummer, name = it.namn.trim(), version = it.version, type = it.livsmedelsTyp)
    }

    fun parseNutrients(body: String): NutrientValues {
        val rows = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NutrientRow.serializer()), body)

        fun value(code: String, unit: String? = null) = rows.firstOrNull {
            it.euroFIRkod == code && (unit == null || it.enhet == unit)
        }?.varde
        return NutrientValues(
            energyKcal = value("ENERC", "kcal"),
            energyKj = value("ENERC", "kJ"),
            protein = value("PROT"),
            fat = value("FAT"),
            carbohydrates = value("CHO"),
            fiber = value("FIBT"),
            salt = value("NACL"),
            sugars = value("SUGAR"),
        )
    }

    @Serializable
    private data class ListItem(val nummer: Int, val namn: String, val version: String = "", val livsmedelsTyp: String? = null)

    @Serializable private data class ListResponse(val livsmedel: List<ListItem> = emptyList())

    @Serializable private data class NutrientRow(val euroFIRkod: String = "", val varde: Double? = null, val enhet: String = "")

    companion object {
        const val BASE = "https://dataportal.livsmedelsverket.se/livsmedel/api/v1"
        private const val PAGE = 5000

        /** "mjölk 3" -> "mjölk* 3*" for prefix matching on every word. Returns null for empty input. */
        fun ftsQuery(input: String): String? {
            val words = input.lowercase().split(Regex("""[^\p{L}\d]+""")).filter { it.isNotBlank() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }
    }
}

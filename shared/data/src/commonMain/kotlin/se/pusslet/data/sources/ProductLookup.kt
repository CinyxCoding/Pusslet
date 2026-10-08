package se.pusslet.data.sources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.pusslet.data.net.NetworkJson
import kotlin.time.Duration.Companion.days

/** A scanned product. Data is crowdsourced (Open Food Facts, ODbL): every field may be missing. */
data class ProductInfo(
    val barcode: String,
    val name: String?,
    val brand: String?,
    val quantity: String?,
    val categories: List<String>,
)

interface ProductLookup {
    /** Null when unknown or offline. */
    suspend fun product(barcode: String): ProductInfo?
}

/** Open Food Facts product lookup only (the search API is unreliable under load). Identifies itself via User-Agent. */
class OpenFoodFactsLookup(private val fetcher: CachedFetcher, private val json: Json = NetworkJson) : ProductLookup {
    override suspend fun product(barcode: String): ProductInfo? {
        val code = barcode.filter { it.isDigit() }
        if (code.length !in 6..14) return null
        val url = "https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS"
        val raw = fetcher.get("off:$code", url, maxAge = 30.days) { it.contains("\"status\"") } ?: return null
        return runCatching { parse(raw.value, code) }.getOrNull()
    }

    fun parse(body: String, code: String): ProductInfo? {
        val dto = json.decodeFromString(Response.serializer(), body)
        if (dto.status != 1 || dto.product == null) return null
        val p = dto.product
        val name = listOf(p.nameSv, p.name, p.genericNameSv).firstOrNull { !it.isNullOrBlank() }?.trim()
        return ProductInfo(
            barcode = code,
            name = name,
            brand = p.brands?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null },
            quantity = p.quantity?.trim()?.ifEmpty { null },
            categories = p.categories.map { it.substringAfter(':') },
        )
    }

    @Serializable
    private data class Product(
        @SerialName("product_name") val name: String? = null,
        @SerialName("product_name_sv") val nameSv: String? = null,
        @SerialName("generic_name_sv") val genericNameSv: String? = null,
        val brands: String? = null,
        val quantity: String? = null,
        @SerialName("categories_tags") val categories: List<String> = emptyList(),
    )

    @Serializable
    private data class Response(val status: Int = 0, val product: Product? = null)

    companion object {
        const val FIELDS = "code,product_name,product_name_sv,generic_name_sv,brands,quantity,categories_tags"
    }
}

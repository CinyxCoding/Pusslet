package se.pusslet.data.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.pusslet.data.net.NetworkJson
import kotlin.time.Duration.Companion.days

data class SchoolUnitSummary(val code: String, val name: String)

data class SchoolUnitDetails(
    val code: String,
    val name: String,
    val municipalityCode: String?,
    val streetAddress: String?,
    val locality: String?,
    val schoolTypes: List<String>,
    val url: String?,
    val latitude: Double?,
    val longitude: Double?,
)

interface SchoolRegistry {
    /** Active school units in a municipality, optionally filtered by name (the API has no name search, ADR-010). */
    suspend fun search(municipalityCode: String, query: String = ""): Sourced<List<SchoolUnitSummary>>?

    suspend fun details(code: String): SchoolUnitDetails?
}

/** Skolverkets Skolenhetsregistret v2 (open API, no key). Lists are cached for a week. */
class SkolverketSchoolRegistry(private val fetcher: CachedFetcher, private val json: Json = NetworkJson) : SchoolRegistry {
    override suspend fun search(municipalityCode: String, query: String): Sourced<List<SchoolUnitSummary>>? {
        val url = "$BASE/school-units?municipality_code=$municipalityCode&status=AKTIV"
        val raw = fetcher.get("skolverket:list:$municipalityCode", url, maxAge = 7.days) { it.contains("\"data\"") } ?: return null
        val all = runCatching { parseList(raw.value) }.getOrNull() ?: return null
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter { q in it.name.lowercase() }.sortedBy { !it.name.lowercase().startsWith(q) }
        return Sourced(
            filtered.sortedWith(
                compareBy({
                    q.isNotEmpty() && !it.name.lowercase().startsWith(q)
                }, { it.name }),
            ),
            raw.fetchedAt,
            raw.stale,
            raw.error,
        )
    }

    override suspend fun details(code: String): SchoolUnitDetails? {
        val raw =
            fetcher.get("skolverket:unit:$code", "$BASE/school-units/$code", maxAge = 30.days) { it.contains("\"data\"") } ?: return null
        return runCatching { parseDetails(raw.value) }.getOrNull()
    }

    fun parseList(body: String): List<SchoolUnitSummary> =
        json.decodeFromString(ListResponse.serializer(), body).data.attributes
            .filter { it.status == "AKTIV" }
            .map { SchoolUnitSummary(it.schoolUnitCode, it.name.trim()) }

    fun parseDetails(body: String): SchoolUnitDetails {
        val dto = json.decodeFromString(DetailsResponse.serializer(), body).data
        val a = dto.attributes
        val visit = a.addresses.firstOrNull { it.type == "BESOKSADRESS" } ?: a.addresses.firstOrNull()
        return SchoolUnitDetails(
            code = dto.schoolUnitCode,
            name = (a.displayName ?: a.schoolName).orEmpty().trim(),
            municipalityCode = a.municipalityCode,
            streetAddress = visit?.streetAddress,
            locality = visit?.locality,
            schoolTypes = a.schoolTypes,
            url = a.url,
            latitude = visit?.geoCoordinates?.latitude?.toDoubleOrNull(),
            longitude = visit?.geoCoordinates?.longitude?.toDoubleOrNull(),
        )
    }

    @Serializable private data class ListItem(val name: String = "", val schoolUnitCode: String = "", val status: String = "")

    @Serializable private data class ListData(val attributes: List<ListItem> = emptyList())

    @Serializable private data class ListResponse(val data: ListData = ListData())

    @Serializable private data class Geo(val latitude: String? = null, val longitude: String? = null)

    @Serializable private data class Address(
        val streetAddress: String? = null,
        val locality: String? = null,
        val type: String? = null,
        val geoCoordinates: Geo? = null,
    )

    @Serializable private data class DetailAttributes(
        val displayName: String? = null,
        val schoolName: String? = null,
        val municipalityCode: String? = null,
        val schoolTypes: List<String> = emptyList(),
        val addresses: List<Address> = emptyList(),
        val url: String? = null,
    )

    @Serializable private data class DetailData(val schoolUnitCode: String = "", val attributes: DetailAttributes = DetailAttributes())

    @Serializable private data class DetailsResponse(val data: DetailData = DetailData())

    companion object {
        const val BASE = "https://api.skolverket.se/skolenhetsregistret/v2"
    }
}

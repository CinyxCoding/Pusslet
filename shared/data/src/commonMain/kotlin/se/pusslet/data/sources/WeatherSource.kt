package se.pusslet.data.sources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.pusslet.core.model.Place
import se.pusslet.core.weather.Forecast
import se.pusslet.core.weather.WeatherPoint
import se.pusslet.data.net.NetworkJson
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

interface WeatherSource {
    suspend fun forecast(place: Place, force: Boolean = false): Sourced<Forecast>?
}

/**
 * SMHI Open Data, SNOW1g v1 point forecast (pmp3g was retired 2026-03-31, see ADR-009).
 * Times are UTC. Position is rounded to ~1 km (coarse location is enough) and cached for one hour.
 */
class SmhiWeatherSource(
    private val fetcher: CachedFetcher,
    private val clock: Clock = Clock.System,
    private val json: Json = NetworkJson,
) : WeatherSource {
    override suspend fun forecast(place: Place, force: Boolean): Sourced<Forecast>? {
        val lat = round2(place.latitude)
        val lon = round2(place.longitude)
        val url = "$BASE/geotype/point/lon/$lon/lat/$lat/data.json?parameters=$PARAMETERS"
        val raw = fetcher.get("smhi:$lat:$lon", url, maxAge = 1.hours, force = force) { it.contains("timeSeries") } ?: return null
        val parsed = runCatching { parse(raw.value, lat, lon, raw.fetchedAt) }.getOrNull() ?: return null
        return Sourced(parsed, raw.fetchedAt, raw.stale, raw.error)
    }

    fun parse(body: String, lat: Double, lon: Double, fetchedAt: Instant = clock.now()): Forecast {
        val dto = json.decodeFromString(SnowResponse.serializer(), body)
        val points = dto.timeSeries.mapNotNull { ts ->
            val d = ts.data
            val t = d.airTemperature?.takeUnless { it >= MISSING } ?: return@mapNotNull null
            WeatherPoint(
                time = Instant.parse(ts.time),
                temperature = t,
                windSpeed = d.windSpeed?.takeUnless { it >= MISSING } ?: 0.0,
                windGust = d.windGust?.takeUnless { it >= MISSING },
                precipitationMm = d.precipitation?.takeUnless { it >= MISSING } ?: 0.0,
                precipitationProbability = d.precipitationProbability?.takeUnless { it >= MISSING }?.roundToInt(),
                frozenPart = d.frozenPart?.takeUnless { it >= MISSING }?.roundToInt(),
                symbol = d.symbol?.takeUnless { it >= MISSING }?.roundToInt(),
                precipitationType = d.precipitationType?.takeUnless { it >= MISSING }?.roundToInt(),
            )
        }
        return Forecast(lat, lon, points, fetchedAt, dto.referenceTime?.let { Instant.parse(it) })
    }

    @Serializable
    private data class SnowData(
        @SerialName("air_temperature") val airTemperature: Double? = null,
        @SerialName("wind_speed") val windSpeed: Double? = null,
        @SerialName("wind_speed_of_gust") val windGust: Double? = null,
        @SerialName("precipitation_amount_mean") val precipitation: Double? = null,
        @SerialName("probability_of_precipitation") val precipitationProbability: Double? = null,
        @SerialName("precipitation_frozen_part") val frozenPart: Double? = null,
        @SerialName("symbol_code") val symbol: Double? = null,
        @SerialName("predominant_precipitation_type_at_surface") val precipitationType: Double? = null,
    )

    @Serializable
    private data class SnowStep(val time: String, val data: SnowData)

    @Serializable
    private data class SnowResponse(val referenceTime: String? = null, val timeSeries: List<SnowStep> = emptyList())

    companion object {
        const val BASE = "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1"
        const val PARAMETERS = "air_temperature,wind_speed,wind_speed_of_gust,precipitation_amount_mean,probability_of_precipitation," +
            "precipitation_frozen_part,symbol_code,predominant_precipitation_type_at_surface"
        private const val MISSING = 9999.0

        fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0
    }
}

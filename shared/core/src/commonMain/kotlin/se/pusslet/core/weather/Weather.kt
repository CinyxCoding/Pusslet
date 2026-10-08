package se.pusslet.core.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.time.Instant

/** One forecast step (SMHI SNOW1g). */
@Serializable
data class WeatherPoint(
    val time: Instant,
    val temperature: Double,
    val windSpeed: Double = 0.0,
    val windGust: Double? = null,
    /** Mean precipitation in mm for the hour (kg/m²). */
    val precipitationMm: Double = 0.0,
    val precipitationProbability: Int? = null,
    /** Share of the precipitation that is frozen, 0–100. */
    val frozenPart: Int? = null,
    /** SMHI weather symbol 1–27. */
    val symbol: Int? = null,
    /** GRIB2/ECMWF precipitation type code (0 none, 1 rain, 5 snow …), see ADR-009. */
    val precipitationType: Int? = null,
)

@Serializable
data class Forecast(
    val latitude: Double,
    val longitude: Double,
    val points: List<WeatherPoint>,
    val fetchedAt: Instant,
    val referenceTime: Instant? = null,
)

enum class WeatherCondition { Clear, PartlyCloudy, Cloudy, Fog, Rain, Showers, Thunder, Sleet, Snow }

/** The part of a day that matters for what children wear: drop-off to pick-up (07–17 local time). */
data class DaySummary(
    val date: LocalDate,
    val minTemperature: Double,
    val maxTemperature: Double,
    /** Lowest wind-chill adjusted temperature during the day. */
    val minFeelsLike: Double,
    val maxWind: Double,
    val totalPrecipitationMm: Double,
    val maxPrecipitationProbability: Int,
    val snowLikely: Boolean,
    val rainLikely: Boolean,
    val condition: WeatherCondition,
    val now: WeatherPoint? = null,
)

object WeatherMath {
    /**
     * Effective (wind chill) temperature as used by SMHI: valid for T <= 10 °C and wind >= 1.3 m/s, wind in m/s.
     * Teff = 13.12 + 0.6215 T − 13.956 v^0.16 + 0.48669 T v^0.16
     */
    fun feelsLike(temperature: Double, windSpeed: Double): Double {
        if (temperature > 10.0 || windSpeed < 1.3) return temperature
        val v = windSpeed.pow(0.16)
        return 13.12 + 0.6215 * temperature - 13.956 * v + 0.48669 * temperature * v
    }

    fun condition(symbol: Int?): WeatherCondition = when (symbol) {
        1, 2 -> WeatherCondition.Clear
        3, 4 -> WeatherCondition.PartlyCloudy
        5, 6 -> WeatherCondition.Cloudy
        7 -> WeatherCondition.Fog
        8, 9, 10 -> WeatherCondition.Showers
        11, 21 -> WeatherCondition.Thunder
        12, 13, 14, 22, 23, 24 -> WeatherCondition.Sleet
        15, 16, 17, 25, 26, 27 -> WeatherCondition.Snow
        18, 19, 20 -> WeatherCondition.Rain
        else -> WeatherCondition.Cloudy
    }

    fun isFrozen(p: WeatherPoint): Boolean =
        (p.frozenPart ?: 0) >= 50 || p.precipitationType in setOf(5, 6, 8) || (p.precipitationType == null && p.temperature <= 0.0)

    /** Summarises the daytime window [fromHour, toHour) of [date] in [zone]; null if the forecast does not cover it. */
    fun summarize(forecast: Forecast, date: LocalDate, zone: TimeZone, fromHour: Int = 7, toHour: Int = 17, now: Instant? = null): DaySummary? {
        val day = forecast.points.filter { p ->
            val local = p.time.toLocalDateTime(zone)
            local.date == date && local.hour in fromHour until toHour
        }.ifEmpty {
            // Late in the day or far ahead (6 h steps): use whatever covers the date.
            forecast.points.filter { it.time.toLocalDateTime(zone).date == date }
        }
        if (day.isEmpty()) return null
        val wet = day.filter { it.precipitationMm >= 0.1 }
        val total = day.sumOf { it.precipitationMm }
        val maxProb = day.maxOf { it.precipitationProbability ?: if (it.precipitationMm > 0.1) 70 else 0 }
        val snow = wet.any { isFrozen(it) } && total >= 0.3
        val rain = wet.any { !isFrozen(it) } && (total >= 0.5 || maxProb >= 60)
        val nowPoint = now?.let { n -> forecast.points.minByOrNull { (it.time - n).absoluteValue } }
        val midday = day.minByOrNull { kotlin.math.abs(it.time.toLocalDateTime(zone).hour - 12) }
        val worst = when {
            snow -> WeatherCondition.Snow
            rain -> day.mapNotNull { it.symbol }.map { condition(it) }.firstOrNull {
                it == WeatherCondition.Rain || it == WeatherCondition.Showers || it == WeatherCondition.Thunder
            } ?: WeatherCondition.Rain
            else -> condition(midday?.symbol)
        }
        return DaySummary(
            date = date,
            minTemperature = day.minOf { it.temperature },
            maxTemperature = day.maxOf { it.temperature },
            minFeelsLike = day.minOf { feelsLike(it.temperature, it.windSpeed) },
            maxWind = day.maxOf { it.windGust?.let { g -> maxOf(it.windSpeed, g * 0.7) } ?: it.windSpeed },
            totalPrecipitationMm = total,
            maxPrecipitationProbability = maxProb,
            snowLikely = snow,
            rainLikely = rain,
            condition = worst,
            now = nowPoint,
        )
    }
}

package se.pusslet.core.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ClothingAdvisorTest {
    private val zone = TimeZone.of("Europe/Stockholm")

    private fun day(
        date: String = "2026-10-08",
        min: Double,
        max: Double = min + 3,
        feels: Double = min,
        wind: Double = 3.0,
        rain: Boolean = false,
        snow: Boolean = false,
    ) = DaySummary(
        date = LocalDate.parse(date),
        minTemperature = min,
        maxTemperature = max,
        minFeelsLike = feels,
        maxWind = wind,
        totalPrecipitationMm = if (rain || snow) 3.0 else 0.0,
        maxPrecipitationProbability = if (rain || snow) 80 else 5,
        snowLikely = snow,
        rainLikely = rain,
        condition = WeatherCondition.Cloudy,
    )

    @Test
    fun rainyAutumnDayForPreschoolerGivesRainGearBootsAndExtraClothes() {
        val advice = ClothingAdvisor.advise(day(min = 8.0, rain = true), ageYears = 4, maxItems = 10)
        assertEquals(ClothingAdvice.RainGearAndBoots, advice.first())
        assertTrue(ClothingAdvice.ExtraClothes in advice)
        assertFalse(ClothingAdvice.LightJacket in advice)
    }

    @Test
    fun olderChildGetsRainJacketNotOverall() {
        val advice = ClothingAdvisor.advise(day(min = 8.0, rain = true), ageYears = 11, maxItems = 10)
        assertTrue(ClothingAdvice.RainJacket in advice)
        assertFalse(ClothingAdvice.RainGearAndBoots in advice)
        assertFalse(ClothingAdvice.ExtraClothes in advice)
    }

    @Test
    fun freezingDayGivesWinterOverallHatAndMittens() {
        val advice = ClothingAdvisor.advise(day(date = "2027-01-12", min = -5.0, feels = -9.0), ageYears = 4, maxItems = 10)
        assertTrue(ClothingAdvice.WinterOverall in advice)
        assertTrue(ClothingAdvice.HatAndMittens in advice)
        assertTrue(ClothingAdvice.WoolLayer in advice)
        assertFalse(ClothingAdvice.Fleece in advice)
    }

    @Test
    fun snowGivesWinterBoots() {
        assertTrue(ClothingAdvice.WinterBoots in ClothingAdvisor.advise(day(min = -1.0, snow = true), ageYears = 6, maxItems = 10))
    }

    @Test
    fun hotSummerDayGivesSunHatWaterAndSunscreen() {
        val advice = ClothingAdvisor.advise(day(date = "2026-07-02", min = 18.0, max = 27.0), ageYears = 4, maxItems = 10)
        assertEquals(ClothingAdvice.SunHatAndWater, advice.first())
        assertTrue(ClothingAdvice.Sunscreen in advice)
        assertTrue(ClothingAdvice.TShirt in advice)
        assertFalse(ClothingAdvice.Reflective in advice)
    }

    @Test
    fun windyDayGivesWindproof() {
        assertTrue(ClothingAdvice.WindProof in ClothingAdvisor.advise(day(date = "2026-09-02", min = 11.0, wind = 12.0), ageYears = 9))
    }

    @Test
    fun darkMonthsRemindAboutReflectors() {
        assertTrue(ClothingAdvice.Reflective in ClothingAdvisor.advise(day(date = "2026-11-10", min = 4.0), ageYears = 8, maxItems = 10))
    }

    @Test
    fun maxItemsIsRespected() {
        assertEquals(2, ClothingAdvisor.advise(day(min = -12.0, snow = true), ageYears = 3, maxItems = 2).size)
    }

    @Test
    fun ageCalculation() {
        assertEquals(9, ClothingAdvisor.ageOn(LocalDate(2017, 3, 14), LocalDate(2026, 10, 8)))
        assertEquals(3, ClothingAdvisor.ageOn(LocalDate(2022, 10, 9), LocalDate(2026, 10, 8)))
        assertEquals(null, ClothingAdvisor.ageOn(null, LocalDate(2026, 10, 8)))
    }

    @Test
    fun feelsLikeUsesWindChillOnlyWhenColdAndWindy() {
        assertEquals(15.0, WeatherMath.feelsLike(15.0, 10.0))
        assertEquals(-2.0, WeatherMath.feelsLike(-2.0, 1.0))
        assertTrue(WeatherMath.feelsLike(0.0, 8.0) < -4.0)
    }

    private fun at(s: String): Instant = LocalDateTime.parse(s).toInstant(zone)

    @Test
    fun summarizeUsesDaytimeHoursOnly() {
        val points = listOf(
            WeatherPoint(at("2026-10-08T05:00"), temperature = -3.0),
            WeatherPoint(at("2026-10-08T08:00"), temperature = 4.0, windSpeed = 2.0, symbol = 6),
            WeatherPoint(at("2026-10-08T12:00"), temperature = 9.0, precipitationMm = 1.2, precipitationProbability = 90, symbol = 19),
            WeatherPoint(at("2026-10-08T16:00"), temperature = 7.0, precipitationMm = 0.4, symbol = 18),
            WeatherPoint(at("2026-10-08T22:00"), temperature = 1.0),
        )
        val s = assertNotNull(WeatherMath.summarize(Forecast(59.3, 18.0, points, at("2026-10-08T06:00")), LocalDate(2026, 10, 8), zone))
        assertEquals(4.0, s.minTemperature)
        assertEquals(9.0, s.maxTemperature)
        assertTrue(s.rainLikely)
        assertFalse(s.snowLikely)
        assertEquals(WeatherCondition.Rain, s.condition)
    }

    @Test
    fun frozenPrecipitationIsSnow() {
        val points = listOf(
            WeatherPoint(at("2027-01-12T09:00"), temperature = -1.0, precipitationMm = 1.0, frozenPart = 100, symbol = 26),
            WeatherPoint(at("2027-01-12T13:00"), temperature = 0.0, precipitationMm = 0.8, frozenPart = 90, symbol = 25),
        )
        val s = assertNotNull(WeatherMath.summarize(Forecast(59.3, 18.0, points, at("2027-01-12T06:00")), LocalDate(2027, 1, 12), zone))
        assertTrue(s.snowLikely)
        assertFalse(s.rainLikely)
        assertEquals(WeatherCondition.Snow, s.condition)
    }

    @Test
    fun symbolMapping() {
        assertEquals(WeatherCondition.Clear, WeatherMath.condition(1))
        assertEquals(WeatherCondition.Thunder, WeatherMath.condition(11))
        assertEquals(WeatherCondition.Snow, WeatherMath.condition(27))
        assertEquals(WeatherCondition.Sleet, WeatherMath.condition(22))
    }
}

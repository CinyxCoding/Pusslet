package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.model.Child
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MemberColor
import se.pusslet.core.time.plusDays
import se.pusslet.core.weather.ClothingAdvice
import se.pusslet.core.weather.Forecast
import se.pusslet.core.weather.WeatherPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In the evening Idag shows tomorrow's weather so outdoor clothes can be planned the night before. */
class TodayWeatherTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 13)
    private val wednesday = tuesday.plusDays(1)
    private val ella = Child("ella", "Ella", MemberColor.Rose, birthDate = LocalDate(2017, 3, 14))

    private fun at(date: LocalDate, h: Int) = LocalDateTime(date, LocalTime(h, 0)).toInstant(zone)

    /** A mild, dry Tuesday and a cold, rainy Wednesday. */
    private val todayPoints = listOf(
        WeatherPoint(at(tuesday, 8), temperature = 11.0, symbol = 2),
        WeatherPoint(at(tuesday, 12), temperature = 14.0, symbol = 2),
        WeatherPoint(at(tuesday, 16), temperature = 13.0, symbol = 3),
        WeatherPoint(at(tuesday, 22), temperature = 9.0, symbol = 3),
    )
    private val tomorrowPoints = listOf(
        WeatherPoint(at(wednesday, 8), temperature = 2.0, symbol = 6),
        WeatherPoint(at(wednesday, 12), temperature = 4.0, precipitationMm = 1.5, precipitationProbability = 90, symbol = 18),
        WeatherPoint(at(wednesday, 16), temperature = 3.0, precipitationMm = 0.8, symbol = 18),
    )

    private fun input(now: LocalTime, points: List<WeatherPoint> = todayPoints + tomorrowPoints, from: LocalTime? = null): TodayInput {
        val base = TodayInput(
            today = tuesday,
            now = now,
            zone = zone,
            me = null,
            people = listOf(FamilyPerson.of(ella)),
            children = listOf(ella),
            occurrences = emptyList(),
            forecast = Forecast(59.33, 18.07, points, at(tuesday, 6)),
        )
        return if (from == null) base else base.copy(tomorrowWeatherFrom = from)
    }

    @Test
    fun beforeSixShowsTodayWithTheCurrentHour() {
        val plan = TodayPlanner.build(input(LocalTime(17, 59)))
        val weather = assertNotNull(plan.weather)
        assertEquals(tuesday, weather.date)
        assertFalse(plan.weatherForTomorrow)
        // "Just nu" is the forecast step closest to the clock, not the warmest hour of the day.
        assertEquals(13.0, weather.now?.temperature)
        assertFalse(ClothingAdvice.RainGearAndBoots in plan.clothing.single().advice)
    }

    @Test
    fun fromSixShowsTomorrowAndItsClothes() {
        val plan = TodayPlanner.build(input(LocalTime(18, 0)))
        val weather = assertNotNull(plan.weather)
        assertEquals(wednesday, weather.date)
        assertTrue(plan.weatherForTomorrow)
        assertNull(weather.now)
        assertEquals(2.0, weather.minTemperature)
        assertTrue(weather.rainLikely)
        assertTrue(ClothingAdvice.RainGearAndBoots in plan.clothing.single().advice)
    }

    @Test
    fun fallsBackToTodayWhenTheForecastStopsTonight() {
        val plan = TodayPlanner.build(input(LocalTime(20, 0), points = todayPoints))
        assertEquals(tuesday, plan.weather?.date)
        assertFalse(plan.weatherForTomorrow)
    }

    @Test
    fun switchTimeCanBeChanged() {
        assertTrue(TodayPlanner.build(input(LocalTime(17, 30), from = LocalTime(17, 0))).weatherForTomorrow)
        assertFalse(TodayPlanner.build(input(LocalTime(16, 59), from = LocalTime(17, 0))).weatherForTomorrow)
    }

    @Test
    fun afterMidnightTheNewDayIsToday() {
        val plan = TodayPlanner.build(input(LocalTime(0, 30)).copy(today = wednesday))
        assertEquals(wednesday, plan.weather?.date)
        assertFalse(plan.weatherForTomorrow)
    }
}

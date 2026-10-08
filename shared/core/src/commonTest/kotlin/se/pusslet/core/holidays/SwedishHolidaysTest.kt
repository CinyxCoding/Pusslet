package se.pusslet.core.holidays

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwedishHolidaysTest {
    private fun dateOf(year: Int, id: HolidayId): LocalDate = SwedishHolidays.forYear(year).single { it.id == id }.date

    @Test
    fun easterSundayKnownDates() {
        val expected = mapOf(
            2019 to LocalDate(2019, 4, 21),
            2020 to LocalDate(2020, 4, 12),
            2021 to LocalDate(2021, 4, 4),
            2022 to LocalDate(2022, 4, 17),
            2023 to LocalDate(2023, 4, 9),
            2024 to LocalDate(2024, 3, 31),
            2025 to LocalDate(2025, 4, 20),
            2026 to LocalDate(2026, 4, 5),
            2027 to LocalDate(2027, 3, 28),
            2028 to LocalDate(2028, 4, 16),
            2029 to LocalDate(2029, 4, 1),
            2030 to LocalDate(2030, 4, 21),
            2038 to LocalDate(2038, 4, 25),
            2285 to LocalDate(2285, 3, 22),
        )
        expected.forEach { (year, date) -> assertEquals(date, SwedishHolidays.easterSunday(year), "Easter $year") }
    }

    @Test
    fun allHolidays2026MatchThePublishedCalendar() {
        val expected = listOf(
            "2026-01-01" to HolidayId.NewYearsDay,
            "2026-01-05" to HolidayId.TwelfthNight,
            "2026-01-06" to HolidayId.Epiphany,
            "2026-02-14" to HolidayId.ValentinesDay,
            "2026-02-17" to HolidayId.ShroveTuesday,
            "2026-03-29" to HolidayId.DaylightSavingStart,
            "2026-04-02" to HolidayId.MaundyThursday,
            "2026-04-03" to HolidayId.GoodFriday,
            "2026-04-04" to HolidayId.EasterEve,
            "2026-04-05" to HolidayId.EasterSunday,
            "2026-04-06" to HolidayId.EasterMonday,
            "2026-04-30" to HolidayId.WalpurgisNight,
            "2026-05-01" to HolidayId.MayDay,
            "2026-05-14" to HolidayId.AscensionDay,
            "2026-05-23" to HolidayId.WhitsunEve,
            "2026-05-24" to HolidayId.WhitSunday,
            "2026-05-31" to HolidayId.MothersDay,
            "2026-06-06" to HolidayId.NationalDay,
            "2026-06-19" to HolidayId.MidsummerEve,
            "2026-06-20" to HolidayId.MidsummerDay,
            "2026-10-25" to HolidayId.DaylightSavingEnd,
            "2026-10-30" to HolidayId.AllSaintsEve,
            "2026-10-31" to HolidayId.AllSaintsDay,
            "2026-11-08" to HolidayId.FathersDay,
            "2026-11-29" to HolidayId.FirstAdvent,
            "2026-12-06" to HolidayId.SecondAdvent,
            "2026-12-13" to HolidayId.ThirdAdvent,
            "2026-12-13" to HolidayId.Lucia,
            "2026-12-20" to HolidayId.FourthAdvent,
            "2026-12-24" to HolidayId.ChristmasEve,
            "2026-12-25" to HolidayId.ChristmasDay,
            "2026-12-26" to HolidayId.BoxingDay,
            "2026-12-31" to HolidayId.NewYearsEve,
        ).map { (d, id) -> LocalDate.parse(d) to id }.toSet()
        val actual = SwedishHolidays.forYear(2026).map { it.date to it.id }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun midsummerAndAllSaintsAreSaturdaysInTheirWindows() {
        for (year in 2000..2100) {
            val midsummer = dateOf(year, HolidayId.MidsummerDay)
            assertEquals(kotlinx.datetime.DayOfWeek.SATURDAY, midsummer.dayOfWeek)
            assertTrue(midsummer in LocalDate(year, 6, 20)..LocalDate(year, 6, 26))
            val allSaints = dateOf(year, HolidayId.AllSaintsDay)
            assertEquals(kotlinx.datetime.DayOfWeek.SATURDAY, allSaints.dayOfWeek)
            assertTrue(allSaints in LocalDate(year, 10, 31)..LocalDate(year, 11, 6))
        }
    }

    @Test
    fun fourthAdventWhenChristmasEveIsSunday() {
        // 2023-12-24 was a Sunday and was itself the fourth Sunday of Advent.
        assertEquals(LocalDate(2023, 12, 24), dateOf(2023, HolidayId.FourthAdvent))
        assertEquals(LocalDate(2023, 12, 3), dateOf(2023, HolidayId.FirstAdvent))
    }

    @Test
    fun redDaysIncludeSundaysAndPublicHolidaysButNotEves() {
        assertTrue(SwedishHolidays.isRedDay(LocalDate(2026, 10, 11))) // Sunday
        assertTrue(SwedishHolidays.isRedDay(LocalDate(2026, 6, 6))) // National day (Saturday)
        assertTrue(SwedishHolidays.isRedDay(LocalDate(2026, 5, 14))) // Ascension
        assertFalse(SwedishHolidays.isRedDay(LocalDate(2026, 12, 24))) // Christmas Eve is de facto, not red
        assertFalse(SwedishHolidays.isRedDay(LocalDate(2026, 4, 2))) // Maundy Thursday
    }

    @Test
    fun nonWorkingDaysIncludeDeFactoHolidays() {
        assertTrue(SwedishHolidays.isNonWorkingDay(LocalDate(2026, 6, 19))) // Midsummer Eve (Friday)
        assertTrue(SwedishHolidays.isNonWorkingDay(LocalDate(2026, 12, 31)))
        assertFalse(SwedishHolidays.isNonWorkingDay(LocalDate(2026, 4, 30))) // Valborg is a working day
    }

    @Test
    fun squeezeDays2026() {
        // Friday after Ascension Day.
        assertEquals(listOf(LocalDate(2026, 5, 15)), SwedishHolidays.squeezeDays(2026).filter { it.monthNumber == 5 })
        assertTrue(SwedishHolidays.isSqueezeDay(LocalDate(2026, 5, 15)))
        assertFalse(SwedishHolidays.isSqueezeDay(LocalDate(2026, 5, 13)))
    }

    @Test
    fun squeezeDayBeforeTuesdayHoliday() {
        // 2027-05-06 Ascension (Thursday) -> Friday 7th; 2029-05-01 is a Tuesday -> Monday 30 April.
        assertTrue(SwedishHolidays.isSqueezeDay(LocalDate(2027, 5, 7)))
        assertTrue(SwedishHolidays.isSqueezeDay(LocalDate(2029, 4, 30)))
    }

    @Test
    fun rangeQuerySpansYears() {
        val list = SwedishHolidays.inRange(LocalDate(2026, 12, 30), LocalDate(2027, 1, 2))
        assertEquals(listOf(HolidayId.NewYearsEve, HolidayId.NewYearsDay), list.map { it.id })
    }

    private val LocalDate.monthNumber: Int get() = month.ordinal + 1
}

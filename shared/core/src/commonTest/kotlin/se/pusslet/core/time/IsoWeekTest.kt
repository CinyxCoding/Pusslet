package se.pusslet.core.time

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoWeekTest {
    @Test
    fun weekOfOrdinaryDate() {
        assertEquals(IsoWeek(2026, 41), LocalDate(2026, 10, 8).isoWeek)
        assertEquals(IsoWeek(2026, 42), LocalDate(2026, 10, 12).isoWeek)
    }

    @Test
    fun yearWith53Weeks2026() {
        // 2026 starts on a Thursday -> 53 weeks; 2027-01-03 (Sunday) still belongs to 2026-W53.
        assertEquals(53, IsoWeek.weeksInYear(2026))
        assertEquals(IsoWeek(2026, 53), LocalDate(2026, 12, 31).isoWeek)
        assertEquals(IsoWeek(2026, 53), LocalDate(2027, 1, 3).isoWeek)
        assertEquals(IsoWeek(2027, 1), LocalDate(2027, 1, 4).isoWeek)
    }

    @Test
    fun yearWith53Weeks2020LeapYearStartingWednesday() {
        assertEquals(53, IsoWeek.weeksInYear(2020))
        assertEquals(IsoWeek(2020, 53), LocalDate(2021, 1, 1).isoWeek)
        assertEquals(52, IsoWeek.weeksInYear(2021))
    }

    @Test
    fun earlyJanuaryCanBelongToPreviousWeekYear() {
        assertEquals(IsoWeek(2021, 52), LocalDate(2022, 1, 2).isoWeek)
        assertEquals(IsoWeek(2022, 1), LocalDate(2022, 1, 3).isoWeek)
    }

    @Test
    fun lateDecemberCanBelongToNextWeekYear() {
        assertEquals(IsoWeek(2025, 1), LocalDate(2024, 12, 30).isoWeek)
        assertEquals(IsoWeek(2025, 1), LocalDate(2025, 1, 1).isoWeek)
    }

    @Test
    fun mondayAndSundayAndDays() {
        val w = IsoWeek(2026, 53)
        assertEquals(LocalDate(2026, 12, 28), w.monday)
        assertEquals(LocalDate(2027, 1, 3), w.sunday)
        assertEquals(7, w.days.size)
        assertTrue(w.days.all { it.isoWeek == w })
        assertEquals(DayOfWeek.MONDAY, w.monday.dayOfWeek)
    }

    @Test
    fun nextAndPreviousCrossYears() {
        assertEquals(IsoWeek(2027, 1), IsoWeek(2026, 53).next())
        assertEquals(IsoWeek(2026, 53), IsoWeek(2027, 1).previous())
        assertEquals(IsoWeek(2025, 52), IsoWeek(2026, 1).previous())
        assertEquals(IsoWeek(2027, 2), IsoWeek(2026, 52).plusWeeks(3))
    }

    @Test
    fun keyRoundTrip() {
        assertEquals("2026-W05", IsoWeek(2026, 5).key)
        assertEquals(IsoWeek(2026, 42), IsoWeek.parse("2026-W42"))
        assertNull(IsoWeek.parse("2025-W53"))
        assertNull(IsoWeek.parse("nonsense"))
    }

    @Test
    fun invalidWeekIsRejected() {
        assertFailsWith<IllegalArgumentException> { IsoWeek(2025, 53) }
        assertFailsWith<IllegalArgumentException> { IsoWeek(2025, 0) }
    }

    @Test
    fun startOfWeekIsMonday() {
        assertEquals(LocalDate(2026, 10, 5), LocalDate(2026, 10, 11).startOfWeek())
        assertEquals(LocalDate(2026, 10, 5), LocalDate(2026, 10, 5).startOfWeek())
    }

    @Test
    fun everyDayForTenYearsIsInAValidWeekWithConsistentMonday() {
        var d = LocalDate(2020, 1, 1)
        while (d < LocalDate(2031, 1, 1)) {
            val w = d.isoWeek
            assertTrue(d in w.days, "$d not in $w")
            assertEquals(d.startOfWeek(), w.monday)
            d = d.plusDays(1)
        }
    }
}

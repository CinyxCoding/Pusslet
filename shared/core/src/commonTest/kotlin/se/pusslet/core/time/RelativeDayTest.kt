package se.pusslet.core.time

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeDayTest {
    private val friday = LocalDate(2026, 10, 9)

    @Test
    fun aStudyDayAWeekAwayIsNextFridayNotFriday() {
        assertEquals(RelativeDay.Today, RelativeDay.of(friday, friday))
        assertEquals(RelativeDay.Tomorrow, RelativeDay.of(LocalDate(2026, 10, 10), friday))
        assertEquals(RelativeDay.ThisWeek, RelativeDay.of(LocalDate(2026, 10, 15), friday), "Thursday")
        assertEquals(RelativeDay.NextWeek, RelativeDay.of(LocalDate(2026, 10, 16), friday), "the Friday a week from today")
        assertEquals(RelativeDay.NextWeek, RelativeDay.of(LocalDate(2026, 10, 22), friday))
        assertEquals(RelativeDay.Other, RelativeDay.of(LocalDate(2026, 10, 23), friday))
        assertEquals(RelativeDay.Other, RelativeDay.of(LocalDate(2026, 10, 8), friday), "yesterday")
    }
}

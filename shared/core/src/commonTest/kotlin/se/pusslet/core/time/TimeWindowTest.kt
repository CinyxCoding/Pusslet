package se.pusslet.core.time

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeWindowTest {
    private fun t(h: Int, m: Int = 0) = LocalTime(h, m)

    @Test
    fun aWindowWithinTheDay() {
        val w = TimeWindow(t(13), t(15))
        assertTrue(t(13) in w)
        assertTrue(t(14, 59) in w)
        assertFalse(t(15) in w)
        assertFalse(t(12, 59) in w)
        assertFalse(t(0) in w)
    }

    @Test
    fun aWindowAcrossMidnight() {
        val w = TimeWindow(t(21), t(6, 30))
        assertTrue(t(21) in w)
        assertTrue(t(23, 59) in w)
        assertTrue(t(0) in w)
        assertTrue(t(6, 29) in w)
        assertFalse(t(6, 30) in w)
        assertFalse(t(12) in w)
        assertFalse(t(20, 59) in w)
    }

    @Test
    fun anEmptyWindowContainsNothing() {
        val w = TimeWindow(t(22), t(22))
        assertFalse(t(22) in w)
        assertFalse(t(3) in w)
    }

    @Test
    fun fromMidnight() {
        val w = TimeWindow(t(0), t(7))
        assertTrue(t(0) in w)
        assertFalse(t(7) in w)
        assertFalse(t(23, 59) in w)
    }
}

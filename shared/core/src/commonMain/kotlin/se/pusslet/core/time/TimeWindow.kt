package se.pusslet.core.time

import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * A daily window of local time, e.g. quiet hours 21:00–06:30. [start] is inclusive and [end] exclusive; a window whose
 * end is before its start runs across midnight, and one with `start == end` is empty.
 */
@Serializable
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    /** Handles windows across midnight (22:00–07:00). */
    operator fun contains(t: LocalTime): Boolean = if (start <= end) t >= start && t < end else t >= start || t < end
}

package se.pusslet.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who looks after the children on one day of a school break (ADR-051): a family member at home, the school's or
 * preschool's break care ("lovomsorg", fritids), or something else written as text ("Mormor", "Läger").
 */
@Serializable
data class BreakDay(
    val care: BreakCare,
    /** The member at home, for [BreakCare.Home]. */
    val memberId: String? = null,
    /** For [BreakCare.Other]. */
    val note: String = "",
)

@Serializable
enum class BreakCare {
    @SerialName("home")
    Home,

    /** Fritids or preschool during the break; has to be booked before [BreakPlan.deadline]. */
    @SerialName("care")
    Care,

    @SerialName("other")
    Other,
}

/**
 * The family's plan for one school break: an all-day event tagged [EventTags.HOLIDAY_BREAK] ("Höstlov"), keyed by the
 * event and its first day ([idFor]). [days] is merged per day, so two parents can fill in different days at once.
 */
@Serializable
data class BreakPlan(
    val id: String,
    val eventId: String,
    val start: LocalDate,
    /** The break's name when the plan was last saved, for the reminder ("Höstlov"). */
    val title: String = "",
    /** ISO date -> who looks after the children. */
    val days: Map<String, BreakDay> = emptyMap(),
    /** "Anmäl lovomsorg senast": the parents are reminded before it. */
    val deadline: LocalDate? = null,
    /** The break care is booked (or not needed). */
    val reported: Boolean = false,
) {
    companion object {
        fun idFor(eventId: String, start: LocalDate): String = "${eventId}_$start"
    }
}

/** One weekday at preschool or fritids: from [start] to [end]. */
@Serializable
data class StayDay(val start: LocalTime, val end: LocalTime)

/**
 * A child's "vistelsetider" (ADR-051): the hours at preschool or fritids each weekday, which the preschool asks the
 * parents for. One per child ([childId] is the id); a weekday that is missing is a day at home.
 */
@Serializable
data class StaySchedule(
    val childId: String,
    val days: Map<DayOfWeek, StayDay> = emptyMap(),
    /** From when it applies, for the text to the preschool. */
    val validFrom: LocalDate? = null,
)

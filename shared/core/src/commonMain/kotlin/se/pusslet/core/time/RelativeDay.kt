package se.pusslet.core.time

import kotlinx.datetime.LocalDate

/** How a day is named relative to today: "idag", "imorgon", "på fredag", "nästa fredag" or a date. */
enum class RelativeDay {
    Today,
    Tomorrow,

    /** Two to six days ahead: the weekday alone is unambiguous ("på fredag"). */
    ThisWeek,

    /** Seven to thirteen days ahead: the same weekday as this week's, so "nästa fredag". */
    NextWeek,

    /** In the past or two weeks or more ahead: say the date. */
    Other,
    ;

    companion object {
        fun of(date: LocalDate, today: LocalDate): RelativeDay = when (date.toEpochDays() - today.toEpochDays()) {
            0L -> Today
            1L -> Tomorrow
            in 2L..6L -> ThisWeek
            in 7L..13L -> NextWeek
            else -> Other
        }
    }
}

package se.pusslet.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One of the child's homes in a shared-custody arrangement. */
@Serializable
data class Household(
    val id: String,
    /** E.g. "Hos pappa" / "Ahmeds". */
    val name: String,
    /** Adult members of this family who live in this household (may be empty for the other family's home). */
    val memberIds: List<String> = emptyList(),
    val color: MemberColor = MemberColor.Slate,
)

@Serializable
enum class CustodyPatternType {
    /** Varannan vecka. */
    @SerialName("alternating_weeks")
    AlternatingWeeks,

    /** 2-2-5-5 (14-day cycle: AABBAAAAABBBBB). */
    @SerialName("2255")
    TwoTwoFiveFive,

    /** 2-2-3 (14-day cycle: AABBAAABBAABBB). */
    @SerialName("223")
    TwoTwoThree,

    /** Explicit cycle of household indices. */
    @SerialName("custom")
    Custom,
}

/** A date range that overrides the regular pattern (summer holiday, Christmas, a swapped weekend). */
@Serializable
data class CustodyException(
    val start: LocalDate,
    /** Inclusive. */
    val end: LocalDate,
    val householdId: String,
    val note: String = "",
)

/**
 * Växelvis boende. The cycle starts on [anchorDate] (the first day in household index 0 for the
 * alternating pattern). Handover happens at [handoverTime] on the first day of each new period.
 */
@Serializable
data class CustodySchedule(
    val id: String,
    val childIds: List<String>,
    val households: List<Household>,
    val pattern: CustodyPatternType = CustodyPatternType.AlternatingWeeks,
    val anchorDate: LocalDate,
    /** Only for [CustodyPatternType.Custom]: household index for each day of the cycle. */
    val customCycle: List<Int> = emptyList(),
    val handoverTime: LocalTime = LocalTime(15, 0),
    val handoverDay: DayOfWeek? = null,
    val exceptions: List<CustodyException> = emptyList(),
)

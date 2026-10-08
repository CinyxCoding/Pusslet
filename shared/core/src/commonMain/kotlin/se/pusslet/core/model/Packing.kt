package se.pusslet.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.pusslet.core.time.IsoWeek

/** "Gympapåse – onsdagar", "Matsäck – när händelse taggad utflykt", "Bibliotekbok – varannan fredag". */
@Serializable
data class PackingRule(
    val id: String,
    val item: String,
    /** Null = applies to every child. */
    val childId: String? = null,
    val trigger: PackingTrigger,
    val active: Boolean = true,
)

@Serializable
sealed interface PackingTrigger {
    /** On given weekdays, every [intervalWeeks] weeks counted from [anchorWeek] (null = every week). */
    @Serializable
    @SerialName("weekly")
    data class Weekly(
        val days: Set<DayOfWeek>,
        val intervalWeeks: Int = 1,
        val anchorWeek: IsoWeek? = null,
    ) : PackingTrigger

    /** Whenever the child has an event with this tag that day. */
    @Serializable
    @SerialName("tag")
    data class EventTag(val tag: String) : PackingTrigger

    /** A one-off date. */
    @Serializable
    @SerialName("date")
    data class OnDate(val date: LocalDate) : PackingTrigger

    /** On custody handover days (things that move between homes). */
    @Serializable
    @SerialName("handover")
    data object Handover : PackingTrigger
}

/** Checked items for one child on one day. Document id: "{date}_{childId}". */
@Serializable
data class PackingChecks(
    val date: LocalDate,
    val childId: String,
    val checked: Map<String, Boolean> = emptyMap(),
) {
    val docId: String get() = docId(date, childId)

    companion object {
        fun docId(date: LocalDate, childId: String) = "${date}_$childId"
    }
}

/** A concrete "ska med idag" line. */
data class PackingItem(
    val key: String,
    val childId: String,
    val text: String,
    val reason: PackingReason,
    val checked: Boolean = false,
)

sealed interface PackingReason {
    data class Rule(val ruleId: String) : PackingReason

    data class ForEvent(val eventTitle: String) : PackingReason

    data object Handover : PackingReason
}

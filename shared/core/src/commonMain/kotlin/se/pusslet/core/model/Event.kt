package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A family calendar event. Times are *floating local times* in [timeZoneId] (normally Europe/Stockholm): a weekly
 * training at 17:30 stays at 17:30 across daylight saving changes. All-day events use midnight and an exclusive end.
 */
@Serializable
data class Event(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val timeZoneId: String = "Europe/Stockholm",
    val location: String = "",
    /** RFC 5545 RRULE value without the "RRULE:" prefix, e.g. "FREQ=WEEKLY;BYDAY=TU". */
    val rrule: String? = null,
    /** Occurrence dates removed from the series. */
    val exDates: List<LocalDate> = emptyList(),
    /** For a moved/changed single occurrence: the series it overrides and the original occurrence date. */
    val seriesId: String? = null,
    val originalDate: LocalDate? = null,
    val participantIds: List<String> = emptyList(),
    val dropOff: Handoff? = null,
    val pickUp: Handoff? = null,
    val tags: List<String> = emptyList(),
    /** Minutes before start. */
    val reminders: List<Int> = emptyList(),
    val note: String = "",
    /** One-off things to bring for this event (in addition to packing rules). */
    val packingItems: List<String> = emptyList(),
    /** Caregivers (e.g. morfar) only see events shared with them. */
    val sharedWithCaregivers: Boolean = true,
    val linkedTaskIds: List<String> = emptyList(),
    val createdBy: String? = null,
    /** Local-only attachments (e.g. a photographed weekly letter). Stored on the device that added them. */
    val attachments: List<Attachment> = emptyList(),
) {
    val isRecurring: Boolean get() = !rrule.isNullOrBlank()
    val isOverride: Boolean get() = seriesId != null
}

/** Who drops off / picks up, and when (defaults to event start/end). */
@Serializable
data class Handoff(val personId: String?, val time: LocalTime? = null)

@Serializable
data class Attachment(val uri: String, val mimeType: String, val name: String = "")

/** Well-known tags. Free-text tags are allowed too; these drive packing rules and contextual cards. */
object EventTags {
    const val TRIP = "utflykt"
    const val PARTY = "kalas"
    const val STUDY_DAY = "studiedag"
    const val HOLIDAY_BREAK = "lov"
    const val TRAINING = "träning"
    const val MEETING = "möte"
    const val WORK = "jobb"
    const val PE = "idrott"

    val all = listOf(TRIP, PARTY, STUDY_DAY, HOLIDAY_BREAK, TRAINING, MEETING, WORK, PE)
}

/** Where an occurrence came from. Only [Family] events are editable. */
@Serializable
enum class EventSource {
    @SerialName("family")
    Family,

    @SerialName("ics")
    Subscription,

    @SerialName("device")
    Device,

    @SerialName("holiday")
    Holiday,
}

/** A concrete occurrence of an event on the timeline (recurrences expanded, overrides applied). */
data class EventOccurrence(
    val event: Event,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Identifies the occurrence within its series (original start date). */
    val occurrenceDate: LocalDate,
    val source: EventSource = EventSource.Family,
    /** Colour/owner for non-family sources (subscription colour, device calendar). */
    val sourceName: String? = null,
    val sourceColor: MemberColor? = null,
) {
    val key: String get() = "${event.id}@$occurrenceDate"
    val allDay: Boolean get() = event.allDay

    fun pickUpTime(): LocalTime? = event.pickUp?.time ?: if (event.allDay) null else end.time

    fun dropOffTime(): LocalTime? = event.dropOff?.time ?: if (event.allDay) null else start.time

    /** Days this occurrence touches (all-day ends are exclusive). */
    fun overlapsDate(date: LocalDate): Boolean {
        val startDate = start.date
        val endDate = if (allDay || end.time == LocalTime(0, 0)) end.date.previousDay() else end.date
        return date >= startDate && date <= maxOf(startDate, endDate)
    }
}

private fun LocalDate.previousDay(): LocalDate = LocalDate.fromEpochDays(toEpochDays() - 1)

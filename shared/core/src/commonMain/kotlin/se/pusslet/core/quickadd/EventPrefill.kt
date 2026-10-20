package se.pusslet.core.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * What "Fyll i själv" in quick add hands the full event editor (UX plan 4.6): the event quick add understood so far, or
 * the typed text as the title when it understood something else. Serializable so it can ride along in a navigation route.
 * [allDay] is only true when the text really was an all-day event ("studiedag fre"); otherwise a missing [startTime]
 * means "no time understood" and the editor keeps its own default.
 */
@Serializable
data class EventPrefill(
    val title: String,
    val date: LocalDate,
    val allDay: Boolean = false,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    /** Inclusive last day of a multi-day all-day event. */
    val endDate: LocalDate? = null,
    val participantIds: List<String> = emptyList(),
    val location: String = "",
    /** RFC 5545 rule without "RRULE:" ("FREQ=WEEKLY;BYDAY=TU"). */
    val rrule: String? = null,
    val tags: List<String> = emptyList(),
    val packingItems: List<String> = emptyList(),
) {
    companion object {
        /**
         * From the preview's actions (with the user's corrections) and the typed [input]. Only the first action counts: it is
         * the one the user typed first. A non-event becomes an event the way the kind chips convert it; [fallbackDate] is the
         * day the sheet was opened for when nothing in the text names one.
         */
        fun from(actions: List<QuickAddAction>, input: String, fallbackDate: LocalDate): EventPrefill {
            val typed = input.trim().replace(WHITESPACE, " ").replaceFirstChar { it.uppercase() }
            val primary = actions.firstOrNull() ?: return EventPrefill(title = typed, date = fallbackDate)
            val event = primary as? QuickAddAction.CreateEvent
            if (event != null) {
                return EventPrefill(
                    title = event.title.ifBlank { typed },
                    date = event.date,
                    allDay = event.allDay,
                    startTime = event.startTime,
                    endTime = event.endTime,
                    endDate = event.endDate,
                    participantIds = event.participantIds,
                    location = event.location,
                    rrule = event.recurrence?.format(),
                    tags = event.tags,
                    packingItems = event.packingItems,
                )
            }
            val converted = QuickAddEdits.convert(primary, ActionKind.Event, fallbackDate) as? QuickAddAction.CreateEvent
            val handoff = primary as? QuickAddAction.AssignHandoff
            return EventPrefill(
                // A hand-off has no title of its own, so the editor gets what was typed.
                title = converted?.title?.takeIf { it.isNotBlank() && handoff == null } ?: typed,
                date = converted?.date ?: fallbackDate,
                startTime = handoff?.time,
                participantIds = converted?.participantIds.orEmpty(),
            )
        }

        private val WHITESPACE = Regex("""\s+""")
    }
}

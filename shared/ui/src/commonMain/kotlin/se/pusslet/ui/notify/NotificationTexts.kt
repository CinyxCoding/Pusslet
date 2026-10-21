package se.pusslet.ui.notify

import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.BriefHandoff
import se.pusslet.core.notify.BriefPacking
import se.pusslet.core.notify.BriefStop
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.today.Greeting
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.and_separator
import se.pusslet.ui.resources.greeting_day
import se.pusslet.ui.resources.greeting_evening
import se.pusslet.ui.resources.greeting_morning
import se.pusslet.ui.resources.greeting_night
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.notify_brief_empty
import se.pusslet.ui.resources.notify_brief_first
import se.pusslet.ui.resources.notify_brief_nobody_dropoff
import se.pusslet.ui.resources.notify_brief_nobody_pickup
import se.pusslet.ui.resources.notify_brief_packing
import se.pusslet.ui.resources.notify_brief_person_dropoff
import se.pusslet.ui.resources.notify_brief_person_pickup
import se.pusslet.ui.resources.notify_brief_sentence
import se.pusslet.ui.resources.notify_brief_you_dropoff
import se.pusslet.ui.resources.notify_brief_you_pickup
import se.pusslet.ui.resources.notify_bundle_handoffs
import se.pusslet.ui.resources.notify_bundle_other
import se.pusslet.ui.resources.notify_bundle_reminders
import se.pusslet.ui.resources.notify_channel_conflicts
import se.pusslet.ui.resources.notify_channel_conflicts_desc
import se.pusslet.ui.resources.notify_channel_evening
import se.pusslet.ui.resources.notify_channel_evening_desc
import se.pusslet.ui.resources.notify_channel_handoffs
import se.pusslet.ui.resources.notify_channel_handoffs_desc
import se.pusslet.ui.resources.notify_channel_morning
import se.pusslet.ui.resources.notify_channel_morning_desc
import se.pusslet.ui.resources.notify_channel_reminders
import se.pusslet.ui.resources.notify_channel_reminders_desc
import se.pusslet.ui.resources.notify_channel_requests
import se.pusslet.ui.resources.notify_channel_requests_desc
import se.pusslet.ui.resources.notify_channel_shopping
import se.pusslet.ui.resources.notify_channel_shopping_desc
import se.pusslet.ui.resources.notify_evening_body
import se.pusslet.ui.resources.notify_evening_title
import se.pusslet.ui.resources.notify_event_allday_tomorrow
import se.pusslet.ui.resources.notify_event_in_days
import se.pusslet.ui.resources.notify_event_in_hours
import se.pusslet.ui.resources.notify_event_in_minutes
import se.pusslet.ui.resources.notify_event_now
import se.pusslet.ui.resources.notify_event_tomorrow_at
import se.pusslet.ui.resources.notify_event_who
import se.pusslet.ui.resources.notify_item_for
import se.pusslet.ui.resources.notify_stop
import se.pusslet.ui.resources.notify_task_today
import se.pusslet.ui.resources.notify_task_today_at
import se.pusslet.ui.resources.notify_you_dropoff
import se.pusslet.ui.resources.notify_you_pickup
import se.pusslet.ui.resources.time_range

/** Between the parts of one line ("Tandläkare · 14:30"), as on Idag. */
private const val SEPARATOR = " · "

/** The words of one notification. [lines] are a summary's rows (Android `InboxStyle`); empty otherwise. */
data class NotificationText(val title: String, val body: String, val lines: List<String> = emptyList()) {
    /** Title and body on one line, for a summary row and for accessibility. */
    val oneLine: String get() = listOf(title, body).filter { it.isNotBlank() }.joinToString(SEPARATOR)
}

/** A channel's name and description in the system settings. */
data class ChannelText(val name: String, val description: String)

/**
 * Texts for [PlannedNotification]s from the string resources, outside composition (`getString`), so a WorkManager worker
 * can call it. Short, person first, no exclamation marks: "Du hämtar Ella 15:45 · Björkängsskolan",
 * "Tandläkare Simon om 30 min", "God morgon" + "Johan lämnar Simon 07:50, du hämtar 15:30. Ska med: gympapåse (Ella).".
 *
 * Lives in shared/ui because the strings do (shared/data does not depend on Compose). The default [lookup] is Compose
 * resources' `getString`, which picks the language outside composition from `Locale.getDefault()` on Android. With
 * AppCompat's per-app language an activity sets that default, but a worker in a process started without one does not,
 * so the worker should call `Locale.setDefault` from `AppCompatDelegate.getApplicationLocales()` first (Compose resources
 * offer no public way to pass a language). Tests pass their own [lookup].
 */
class NotificationTexts(private val lookup: StringLookup = ComposeStrings) {
    /** Reads one string resource with its format arguments. */
    fun interface StringLookup {
        suspend fun get(res: StringResource, args: List<Any>): String
    }

    /** The app's string resources, in the language `getString` picks. */
    object ComposeStrings : StringLookup {
        override suspend fun get(res: StringResource, args: List<Any>): String = getString(res, *args.toTypedArray())
    }

    private suspend fun str(res: StringResource, vararg args: Any): String = lookup.get(res, args.toList())

    suspend fun text(n: PlannedNotification): NotificationText = when (val p = n.payload) {
        is NotificationPayload.EventReminder -> event(p)
        is NotificationPayload.Handoff -> NotificationText(handoffTitle(p), p.place)
        is NotificationPayload.Task -> NotificationText(
            p.title,
            p.time?.let { str(Res.string.notify_task_today_at, formatTime(it)) } ?: str(Res.string.notify_task_today),
        )
        is NotificationPayload.Morning -> NotificationText(greeting(p.greeting), morningBody(p))
        is NotificationPayload.Evening -> NotificationText(
            str(Res.string.notify_evening_title),
            str(Res.string.notify_evening_body, packing(p.packing)),
        )
        is NotificationPayload.Bundle -> bundle(p)
    }

    suspend fun channel(kind: NotificationKind): ChannelText = when (kind) {
        NotificationKind.Reminder -> channel(Res.string.notify_channel_reminders, Res.string.notify_channel_reminders_desc)
        NotificationKind.HandoffSoon -> channel(Res.string.notify_channel_handoffs, Res.string.notify_channel_handoffs_desc)
        NotificationKind.Morning -> channel(Res.string.notify_channel_morning, Res.string.notify_channel_morning_desc)
        NotificationKind.Evening -> channel(Res.string.notify_channel_evening, Res.string.notify_channel_evening_desc)
        NotificationKind.Requests -> channel(Res.string.notify_channel_requests, Res.string.notify_channel_requests_desc)
        NotificationKind.Shopping -> channel(Res.string.notify_channel_shopping, Res.string.notify_channel_shopping_desc)
        NotificationKind.Conflicts -> channel(Res.string.notify_channel_conflicts, Res.string.notify_channel_conflicts_desc)
    }

    private suspend fun channel(name: StringResource, description: StringResource) = ChannelText(str(name), str(description))

    /** "Tandläkare Simon om 30 min" and "14:30–15:00 · Folktandvården". */
    private suspend fun event(p: NotificationPayload.EventReminder): NotificationText {
        val what = if (p.children.isEmpty()) p.title else str(Res.string.notify_event_who, p.title, joinNames(p.children))
        if (p.allDay) return NotificationText(str(Res.string.notify_event_allday_tomorrow, what), p.location.trim())
        val m = p.minutesBefore
        val title = when {
            m <= 0 -> str(Res.string.notify_event_now, what)
            m >= MINUTES_PER_DAY -> {
                val days = m / MINUTES_PER_DAY
                if (days ==
                    1
                ) {
                    str(Res.string.notify_event_tomorrow_at, what, formatTime(p.start.time))
                } else {
                    str(Res.string.notify_event_in_days, what, days)
                }
            }
            m % MINUTES_PER_HOUR == 0 -> str(Res.string.notify_event_in_hours, what, m / MINUTES_PER_HOUR)
            else -> str(Res.string.notify_event_in_minutes, what, m)
        }
        val time = if (p.end.date == p.start.date && p.end.time > p.start.time) {
            str(Res.string.time_range, formatTime(p.start.time), formatTime(p.end.time))
        } else {
            formatTime(p.start.time)
        }
        return NotificationText(title, listOf(time, p.location.trim()).filter { it.isNotEmpty() }.joinToString(SEPARATOR))
    }

    /** "Du hämtar Ella 15:45", "Du lämnar Ella och Simon 08:05". */
    private suspend fun handoffTitle(p: NotificationPayload.Handoff): String {
        val what = str(Res.string.notify_stop, joinNames(p.children), formatTime(p.time))
        return str(if (p.kind == HandoffKind.PickUp) Res.string.notify_you_pickup else Res.string.notify_you_dropoff, what)
    }

    private suspend fun greeting(g: Greeting): String = str(
        when (g) {
            Greeting.Morning -> Res.string.greeting_morning
            Greeting.Day -> Res.string.greeting_day
            Greeting.Evening -> Res.string.greeting_evening
            Greeting.Night -> Res.string.greeting_night
        },
    )

    /** "Johan lämnar Simon 07:50, du hämtar 15:30. Ska med: gympapåse (Ella)." */
    private suspend fun morningBody(p: NotificationPayload.Morning): String {
        val sentences = ArrayList<String>()
        if (p.handoffs.isNotEmpty()) {
            val clauses = p.handoffs.mapIndexed { i, line ->
                clause(
                    line,
                    sameChildren =
                        i > 0 && children(p.handoffs[i - 1]) == children(line),
                )
            }
            sentences += str(Res.string.notify_brief_sentence, clauses.joinToString(str(Res.string.list_separator)).capitalized())
        }
        if (p.packing.isNotEmpty()) sentences += str(Res.string.notify_brief_packing, packing(p.packing))
        if (sentences.isEmpty()) {
            val first = p.first
            sentences += if (first == null) {
                str(Res.string.notify_brief_empty)
            } else {
                str(Res.string.notify_brief_first, listOfNotNull(first.title, first.time?.let(::formatTime)).joinToString(" "))
            }
        }
        return sentences.joinToString(" ")
    }

    /** One hand-off as a line of its own, as Morgonkollen says it: "Du hämtar Ella 15:45" (the Idag widget). */
    suspend fun handoffLine(line: BriefHandoff): String = clause(line, sameChildren = false).capitalized()

    /** What is left to bring, as Morgonkollen lists it: "gympapåse (Ella), regnkläder (Simon)" (the Idag widget). */
    suspend fun packingList(packing: List<BriefPacking>): String = packing(packing)

    /** "Johan lämnar Simon 07:50", "du hämtar 15:30" (the same children as the clause before are not repeated). */
    private suspend fun clause(line: BriefHandoff, sameChildren: Boolean): String {
        val what = stops(line.stops, withChildren = !sameChildren)
        val pickUp = line.kind == HandoffKind.PickUp
        return when {
            line.isMe -> str(if (pickUp) Res.string.notify_brief_you_pickup else Res.string.notify_brief_you_dropoff, what)
            line.person != null -> str(
                if (pickUp) Res.string.notify_brief_person_pickup else Res.string.notify_brief_person_dropoff,
                line.person.orEmpty(),
                what,
            )
            else -> str(if (pickUp) Res.string.notify_brief_nobody_pickup else Res.string.notify_brief_nobody_dropoff, what)
        }
    }

    private fun children(line: BriefHandoff): Set<String> = line.stops.mapTo(LinkedHashSet()) { it.child }

    /** "Simon 07:50 och Ella 08:05"; children at the same time share it ("Simon och Ella 07:50"); or only the times. */
    private suspend fun stops(stops: List<BriefStop>, withChildren: Boolean): String {
        val groups = ArrayList<Pair<LocalTime, MutableList<String>>>()
        stops.forEach { stop ->
            val sameTime = groups.lastOrNull()?.takeIf { it.first == stop.time }
            if (sameTime != null) sameTime.second += stop.child else groups += stop.time to mutableListOf(stop.child)
        }
        val parts = groups.map { (time, names) ->
            if (withChildren) str(Res.string.notify_stop, joinNames(names), formatTime(time)) else formatTime(time)
        }
        return joinNames(parts)
    }

    /** "gympapåse (Ella), matsäck, regnkläder (Simon)". */
    private suspend fun packing(packing: List<BriefPacking>): String {
        val separator = str(Res.string.list_separator)
        return packing.map { cp ->
            val items = cp.items.joinToString(separator) { item -> item.replaceFirstChar { it.lowercase() } }
            str(Res.string.notify_item_for, items, cp.child)
        }.joinToString(separator)
    }

    private suspend fun bundle(p: NotificationPayload.Bundle): NotificationText {
        val count = p.items.size
        val title = when (p.kind) {
            NotificationKind.Reminder -> str(Res.string.notify_bundle_reminders, count)
            NotificationKind.HandoffSoon -> str(Res.string.notify_bundle_handoffs, count)
            else -> str(Res.string.notify_bundle_other, count)
        }
        val lines = p.items.map { text(it).oneLine }
        return NotificationText(title, lines.joinToString(SEPARATOR), lines)
    }

    /** "Sara och Johan", "Ella, Simon och Vera". */
    private suspend fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(str(Res.string.list_separator)) + str(Res.string.and_separator) + names.last()
    }

    private fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

    private companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}

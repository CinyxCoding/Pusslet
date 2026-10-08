package se.pusslet.core.ics

import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Event
import se.pusslet.core.recurrence.EventExpander
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A parsed calendar feed. */
data class IcsCalendar(
    val name: String?,
    val defaultZoneId: String?,
    val events: List<Event>,
    /** Lines or components we could not understand (for diagnostics; never shown with personal data). */
    val warnings: List<String> = emptyList(),
)

/** Abstraction so another implementation (e.g. a platform library) could be swapped in. */
interface CalendarFeedParser {
    fun parse(text: String, idPrefix: String, fallbackZone: TimeZone): IcsCalendar
}

/**
 * RFC 5545 parser for read-only subscriptions: VEVENT with DTSTART/DTEND/DURATION (DATE, floating, UTC or TZID),
 * RRULE, EXDATE, RDATE, RECURRENCE-ID overrides and STATUS:CANCELLED. Time zones: IANA ids, common Windows names and
 * a fixed-offset fallback from VTIMEZONE. Unknown components are skipped, never fatal.
 */
class IcsParser : CalendarFeedParser {
    internal data class ContentLine(val name: String, val params: Map<String, String>, val value: String)

    private class RawEvent {
        val props = ArrayList<ContentLine>()

        fun first(name: String) = props.firstOrNull { it.name == name }

        fun all(name: String) = props.filter { it.name == name }
    }

    // Straight walk over RFC 5545 properties; each branch handles one property.
    @Suppress("CyclomaticComplexMethod")
    override fun parse(text: String, idPrefix: String, fallbackZone: TimeZone): IcsCalendar {
        val lines = unfold(text).mapNotNull { parseLine(it) }
        val warnings = ArrayList<String>()
        var calName: String? = null
        var calZone: String? = null
        val rawEvents = ArrayList<RawEvent>()
        val vtimezones = HashMap<String, UtcOffset>()

        var current: RawEvent? = null
        val stack = ArrayList<String>()
        var tzId: String? = null
        var tzStandardOffset: UtcOffset? = null

        for (line in lines) {
            when (line.name) {
                "BEGIN" -> {
                    val comp = line.value.uppercase()
                    stack += comp
                    if (comp == "VEVENT" && stack.size == 2) current = RawEvent()
                    if (comp == "VTIMEZONE") {
                        tzId = null
                        tzStandardOffset = null
                    }
                }
                "END" -> {
                    val comp = line.value.uppercase()
                    if (comp == "VEVENT" && current != null && stack.size == 2) {
                        rawEvents += current
                        current = null
                    }
                    if (comp == "VTIMEZONE") {
                        val id = tzId
                        val off = tzStandardOffset
                        if (id != null && off != null) vtimezones[id] = off
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
                else -> {
                    val top = stack.lastOrNull()
                    when {
                        top == "VEVENT" && current != null -> current.props += line
                        top == "VCALENDAR" && line.name == "X-WR-CALNAME" -> calName = unescape(line.value)
                        top == "VCALENDAR" && line.name == "X-WR-TIMEZONE" -> calZone = line.value.trim()
                        top == "VTIMEZONE" && line.name == "TZID" -> tzId = line.value.trim()
                        top == "STANDARD" && line.name == "TZOFFSETTO" -> tzStandardOffset = parseOffset(line.value)
                    }
                }
            }
        }

        val calendarZone = calZone?.let { resolveZone(it, emptyMap(), fallbackZone) } ?: fallbackZone
        val events = ArrayList<Event>()
        val masterExDates = HashMap<String, MutableList<LocalDate>>()

        for (raw in rawEvents) {
            val result = runCatching { toEvent(raw, idPrefix, calendarZone, vtimezones) }
            result.exceptionOrNull()?.let { warnings += "Skipped VEVENT: ${it::class.simpleName}" }
            val mapped = result.getOrNull() ?: continue
            if (mapped.cancelled) {
                if (mapped.event.seriesId != null && mapped.event.originalDate != null) {
                    masterExDates.getOrPut(mapped.event.seriesId) { ArrayList() } += mapped.event.originalDate
                }
                continue
            }
            events += mapped.event
            events += mapped.extraRDates
        }
        // Apply cancelled occurrences to their masters; de-duplicate repeated UIDs (last one wins).
        val withEx = events.map { e -> masterExDates[e.id]?.let { e.copy(exDates = e.exDates + it) } ?: e }
        val deduped = withEx.associateBy { it.id }.values.toList()
        return IcsCalendar(calName, calZone, deduped, warnings)
    }

    private data class Mapped(val event: Event, val cancelled: Boolean, val extraRDates: List<Event>)

    private fun toEvent(raw: RawEvent, idPrefix: String, calendarZone: TimeZone, vtz: Map<String, UtcOffset>): Mapped? {
        val uid = raw.first("UID")?.value?.trim()?.ifEmpty { null } ?: return null
        val dtStart = raw.first("DTSTART") ?: return null
        val start = parseDateValue(dtStart, calendarZone, vtz)
        val allDay = start.isDate
        val end: IcsTime = raw.first("DTEND")?.let { parseDateValue(it, calendarZone, vtz) }
            ?: raw.first("DURATION")?.let { d ->
                val dur = parseDuration(d.value) ?: Duration.ZERO
                start.copy(local = EventExpander.addWallClock(start.local, dur))
            }
            ?: if (allDay) start.copy(local = EventExpander.addWallClock(start.local, 1.days)) else start
        val endLocal = if (end.zone == start.zone) end.local else end.local.toInstant(end.zone).toLocalDateTime(start.zone)

        val recurrenceId = raw.first("RECURRENCE-ID")?.let { parseDateValue(it, calendarZone, vtz) }
        val baseId = "$idPrefix$uid"
        val id = if (recurrenceId != null) "$baseId@${recurrenceId.local.date}" else baseId
        val exDates = raw.all("EXDATE").flatMap { ex ->
            ex.value.split(',').filter { it.isNotBlank() }.map { v ->
                val t = parseDateValue(ex.copy(value = v), calendarZone, vtz)
                if (t.zone == start.zone) t.local.date else t.local.toInstant(t.zone).toLocalDateTime(start.zone).date
            }
        }
        val categories = raw.all("CATEGORIES").flatMap { c -> c.value.split(',').map { unescape(it).trim().lowercase() } }
            .filter { it.isNotEmpty() }
        val event = Event(
            id = id,
            title = raw.first("SUMMARY")?.let { unescape(it.value) }?.trim().orEmpty().ifEmpty { "–" },
            start = start.local,
            end = if (endLocal < start.local) start.local else endLocal,
            allDay = allDay,
            timeZoneId = zoneId(start.zone),
            location = raw.first("LOCATION")?.let { unescape(it.value) }.orEmpty(),
            rrule = if (recurrenceId == null) raw.first("RRULE")?.value?.trim() else null,
            exDates = exDates,
            seriesId = if (recurrenceId != null) baseId else null,
            originalDate = recurrenceId?.let {
                if (it.zone == start.zone) it.local.date else it.local.toInstant(it.zone).toLocalDateTime(start.zone).date
            },
            note = raw.first("DESCRIPTION")?.let { unescape(it.value) }.orEmpty(),
            tags = categories,
        )
        val cancelled = raw.first("STATUS")?.value?.trim()?.uppercase() == "CANCELLED"
        // RDATE: extra single occurrences of the same event.
        val duration = EventExpander.wallClockDuration(event.start, event.end)
        val rdates = raw.all("RDATE").flatMap { r -> r.value.split(',').filter { it.isNotBlank() } }.mapIndexed { i, v ->
            val t = parseDateValue(dtStart.copy(value = v), calendarZone, vtz)
            event.copy(
                id = "$baseId#r$i",
                rrule = null,
                exDates = emptyList(),
                start = t.local,
                end = EventExpander.addWallClock(t.local, duration),
            )
        }
        return Mapped(event, cancelled, if (recurrenceId == null) rdates else emptyList())
    }

    internal data class IcsTime(val local: LocalDateTime, val zone: TimeZone, val isDate: Boolean)

    private fun parseDateValue(line: ContentLine, calendarZone: TimeZone, vtz: Map<String, UtcOffset>): IcsTime {
        val v = line.value.trim()
        val isDate = line.params["VALUE"]?.uppercase() == "DATE" || (v.length == 8 && v.all { it.isDigit() })
        if (isDate) {
            return IcsTime(LocalDateTime(parseDate(v.take(8)), LocalTime(0, 0)), calendarZone, isDate = true)
        }
        val date = parseDate(v.substring(0, 8))
        val time = parseTime(v.substring(9, 15))
        val local = LocalDateTime(date, time)
        return when {
            v.endsWith("Z") -> IcsTime(local, TimeZone.UTC, false)
            line.params["TZID"] != null -> IcsTime(local, resolveZone(line.params.getValue("TZID"), vtz, calendarZone), false)
            // Floating time: interpret in the calendar's zone.
            else -> IcsTime(local, calendarZone, false)
        }
    }

    companion object {
        /** RFC 5545 §3.1: lines are folded with CRLF followed by a space or tab. */
        internal fun unfold(text: String): List<String> {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val out = ArrayList<String>()
            for (line in normalized.split('\n')) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                    out[out.lastIndex] = out.last() + line.substring(1)
                } else if (line.isNotEmpty()) {
                    out += line
                }
            }
            return out
        }

        internal fun parseLine(line: String): ContentLine? {
            // Find the first ':' that is not inside a quoted parameter value.
            var inQuotes = false
            var colon = -1
            for (i in line.indices) {
                val c = line[i]
                if (c == '"') inQuotes = !inQuotes
                if (c == ':' && !inQuotes) {
                    colon = i
                    break
                }
            }
            if (colon <= 0) return null
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val segments = splitParams(head)
            val name = segments.first().trim().uppercase()
            val params = segments.drop(1).mapNotNull { p ->
                val eq = p.indexOf('=')
                if (eq <= 0) null else p.substring(0, eq).trim().uppercase() to p.substring(eq + 1).trim().removeSurrounding("\"")
            }.toMap()
            return ContentLine(name, params, value)
        }

        private fun splitParams(head: String): List<String> {
            val parts = ArrayList<String>()
            val sb = StringBuilder()
            var inQuotes = false
            for (c in head) {
                when {
                    c == '"' -> {
                        inQuotes = !inQuotes
                        sb.append(c)
                    }
                    c == ';' && !inQuotes -> {
                        parts += sb.toString()
                        sb.clear()
                    }
                    else -> sb.append(c)
                }
            }
            parts += sb.toString()
            return parts
        }

        fun unescape(v: String): String = buildString {
            var i = 0
            while (i < v.length) {
                val c = v[i]
                if (c == '\\' && i + 1 < v.length) {
                    when (val n = v[i + 1]) {
                        'n', 'N' -> append('\n')
                        ',', ';', '\\', ':' -> append(n)
                        else -> append(n)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        fun parseDate(v: String): LocalDate = LocalDate(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())

        fun parseTime(v: String): LocalTime = LocalTime(v.substring(0, 2).toInt(), v.substring(2, 4).toInt(), v.substring(4, 6).toInt())

        /** ISO 8601 / RFC 5545 duration: P1D, PT1H30M, P1W, -PT15M. */
        fun parseDuration(v: String): Duration? {
            val m =
                Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").matchEntire(v.trim()) ?: return null

            fun part(group: Int): Long = m.groupValues[group].toLongOrNull() ?: 0
            val total = part(2) * 7.days.inWholeSeconds + part(3) * 1.days.inWholeSeconds +
                part(4) * 1.hours.inWholeSeconds + part(5) * 1.minutes.inWholeSeconds + part(6)
            return (if (m.groupValues[1] == "-") -total else total).seconds
        }

        private fun parseOffset(v: String): UtcOffset? {
            val m = Regex("""^([+-])(\d{2})(\d{2})(\d{2})?$""").matchEntire(v.trim()) ?: return null
            val (sign, hh, mm, _) = m.destructured
            val s = if (sign == "-") -1 else 1
            return UtcOffset(hours = s * hh.toInt(), minutes = s * mm.toInt())
        }

        /** Microsoft Exchange/Outlook often writes Windows zone names instead of IANA ids. */
        private val windowsZones = mapOf(
            "W. Europe Standard Time" to "Europe/Stockholm",
            "Central Europe Standard Time" to "Europe/Budapest",
            "Central European Standard Time" to "Europe/Warsaw",
            "Romance Standard Time" to "Europe/Paris",
            "GMT Standard Time" to "Europe/London",
            "Greenwich Standard Time" to "Atlantic/Reykjavik",
            "FLE Standard Time" to "Europe/Helsinki",
            "E. Europe Standard Time" to "Europe/Chisinau",
            "Russian Standard Time" to "Europe/Moscow",
            "UTC" to "UTC",
            "Coordinated Universal Time" to "UTC",
            "Eastern Standard Time" to "America/New_York",
            "Central Standard Time" to "America/Chicago",
            "Mountain Standard Time" to "America/Denver",
            "Pacific Standard Time" to "America/Los_Angeles",
            "Tokyo Standard Time" to "Asia/Tokyo",
            "China Standard Time" to "Asia/Shanghai",
            "India Standard Time" to "Asia/Kolkata",
            "AUS Eastern Standard Time" to "Australia/Sydney",
        )

        fun resolveZone(tzid: String, vtz: Map<String, UtcOffset>, fallback: TimeZone = TimeZone.UTC): TimeZone {
            val id = tzid.trim().removePrefix("/")
            runCatching { return TimeZone.of(id) }
            windowsZones[id]?.let { return TimeZone.of(it) }
            // "(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna" and friends.
            if (id.contains("Stockholm", ignoreCase = true) || id.contains("Amsterdam", ignoreCase = true)) {
                return TimeZone.of("Europe/Stockholm")
            }
            Regex("""[A-Za-z]+/[A-Za-z_]+""").find(id)?.let { m -> runCatching { return TimeZone.of(m.value) } }
            vtz[tzid]?.let { return FixedOffsetTimeZone(it) }
            return fallback
        }

        private fun zoneId(zone: TimeZone): String = zone.id
    }
}

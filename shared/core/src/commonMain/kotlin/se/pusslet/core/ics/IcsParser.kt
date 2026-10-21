package se.pusslet.core.ics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Event
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.recurrence.RRule
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
 * RRULE, EXDATE, RDATE, RECURRENCE-ID overrides and STATUS:CANCELLED. Time zones: IANA ids, common Windows names and,
 * for any other TZID, its VTIMEZONE: daylight saving rules are matched to a known zone ([VTimeZone.resolve]), else the
 * standard offset is used. Unknown components are skipped, never fatal.
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
        val vtimezones = HashMap<String, VTimeZone>()

        var current: RawEvent? = null
        val stack = ArrayList<String>()
        var tzId: String? = null
        val tzObservances = ArrayList<VTimeZone.Observance>()
        var observance: ArrayList<ContentLine>? = null

        for (line in lines) {
            when (line.name) {
                "BEGIN" -> {
                    val comp = line.value.uppercase()
                    stack += comp
                    if (comp == "VEVENT" && stack.size == 2) current = RawEvent()
                    if (comp == "VTIMEZONE") {
                        tzId = null
                        tzObservances.clear()
                    }
                    if ((comp == "STANDARD" || comp == "DAYLIGHT") && stack.getOrNull(stack.lastIndex - 1) == "VTIMEZONE") {
                        observance = ArrayList()
                    }
                }
                "END" -> {
                    val comp = line.value.uppercase()
                    if (comp == "VEVENT" && current != null && stack.size == 2) {
                        rawEvents += current
                        current = null
                    }
                    val props = observance
                    if ((comp == "STANDARD" || comp == "DAYLIGHT") && props != null) {
                        parseObservance(comp == "DAYLIGHT", props)?.let { tzObservances += it }
                        observance = null
                    }
                    if (comp == "VTIMEZONE") {
                        val id = tzId
                        if (id != null && tzObservances.isNotEmpty()) vtimezones[id] = VTimeZone(id, tzObservances.toList())
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
                        top == "STANDARD" || top == "DAYLIGHT" -> observance?.add(line)
                    }
                }
            }
        }

        // The years the events start in decide which daylight saving rules a VTIMEZONE is compared with.
        val years = rawEvents.mapNotNull { e -> e.first("DTSTART")?.value?.trim()?.take(4)?.toIntOrNull() }.filter { it in YEARS }
        val zones = Zones(vtimezones, years)
        val calendarZone = calZone?.let { zones.of(it, fallbackZone) } ?: fallbackZone
        val events = ArrayList<Event>()
        val masterExDates = HashMap<String, MutableList<LocalDate>>()

        for (raw in rawEvents) {
            val result = runCatching { toEvent(raw, idPrefix, calendarZone, zones) }
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
        return IcsCalendar(calName, calZone, deduped, warnings + zones.warnings)
    }

    /** Looks up TZIDs: known names first, then the feed's own VTIMEZONE, resolved once per TZID. */
    private class Zones(private val definitions: Map<String, VTimeZone>, private val years: List<Int>) {
        private val resolved = HashMap<String, TimeZone?>()
        val warnings = ArrayList<String>()

        fun of(tzid: String, fallback: TimeZone): TimeZone = knownZone(tzid) ?: fromDefinition(tzid.trim()) ?: fallback

        private fun fromDefinition(tzid: String): TimeZone? = resolved.getOrPut(tzid) {
            val result = definitions[tzid]?.resolve(years)
            // The TZID itself may be a name the user typed in Outlook; never put it in a warning.
            if (result != null && !result.matched) warnings += "VTIMEZONE with unknown daylight saving rules; using its standard offset"
            result?.zone
        }
    }

    private fun parseObservance(daylight: Boolean, props: List<ContentLine>): VTimeZone.Observance? {
        fun prop(name: String) = props.firstOrNull { it.name == name }
        val to = prop("TZOFFSETTO")?.let { parseOffset(it.value) } ?: return null
        val from = prop("TZOFFSETFROM")?.let { parseOffset(it.value) } ?: to
        val start = prop("DTSTART")?.value?.trim()?.let { runCatching { parseLocal(it) }.getOrNull() }
            ?: LocalDateTime(OBSERVANCE_EPOCH, 1, 1, 0, 0)
        val rule = prop("RRULE")?.value?.let { RRule.parse(it.trim(), TimeZone.UTC) }
        val rDates = props.filter { it.name == "RDATE" }.flatMap { r -> r.value.split(',') }
            .mapNotNull { v -> runCatching { parseLocal(v.trim()) }.getOrNull() }
        return VTimeZone.Observance(daylight, from, to, start, rule, rDates)
    }

    private fun parseLocal(v: String): LocalDateTime = if (v.length == DATE_LENGTH) {
        LocalDateTime(parseDate(v), LocalTime(0, 0))
    } else {
        LocalDateTime(parseDate(v.substring(0, DATE_LENGTH)), parseTime(v.substring(DATE_LENGTH + 1, DATE_LENGTH + 7)))
    }

    private data class Mapped(val event: Event, val cancelled: Boolean, val extraRDates: List<Event>)

    private fun toEvent(raw: RawEvent, idPrefix: String, calendarZone: TimeZone, vtz: Zones): Mapped? {
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

    private fun parseDateValue(line: ContentLine, calendarZone: TimeZone, vtz: Zones): IcsTime {
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
            line.params["TZID"] != null -> IcsTime(local, vtz.of(line.params.getValue("TZID"), calendarZone), false)
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

        /** A TZID we know by name, or [fallback]. Feeds' own VTIMEZONE definitions are handled inside [parse]. */
        fun resolveZone(tzid: String, fallback: TimeZone = TimeZone.UTC): TimeZone = knownZone(tzid) ?: fallback

        /** IANA ids, Windows names and "(UTC+01:00) Amsterdam, … Stockholm"-style labels; null when the name says nothing. */
        private fun knownZone(tzid: String): TimeZone? {
            val id = tzid.trim().removePrefix("/")
            runCatching { return TimeZone.of(id) }
            windowsZones[id]?.let { return TimeZone.of(it) }
            // "(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna" and friends.
            if (id.contains("Stockholm", ignoreCase = true) || id.contains("Amsterdam", ignoreCase = true)) {
                return TimeZone.of("Europe/Stockholm")
            }
            Regex("""[A-Za-z]+/[A-Za-z_]+""").find(id)?.let { m -> runCatching { return TimeZone.of(m.value) } }
            return null
        }

        private val YEARS = 1900..2200
        private const val OBSERVANCE_EPOCH = 1970
        private const val DATE_LENGTH = 8

        private fun zoneId(zone: TimeZone): String = zone.id
    }
}

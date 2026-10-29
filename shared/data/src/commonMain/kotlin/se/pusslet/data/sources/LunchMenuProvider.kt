package se.pusslet.data.sources

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.SchoolLunch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A school lunch menu source. Implementations: skolmaten.se RSS, Matilda RSS, any RSS URL. Manual entries live in Room. */
interface LunchMenuProvider {
    fun supports(source: LunchSource): Boolean

    suspend fun lunches(source: LunchSource, force: Boolean = false): Sourced<List<SchoolLunch>>?
}

/** Asks the first provider that knows the child's source. */
class LunchMenus(private val providers: List<LunchMenuProvider>) : LunchMenuProvider {
    override fun supports(source: LunchSource) = providers.any { it.supports(source) }

    override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? =
        providers.firstOrNull { it.supports(source) }?.lunches(source, force)
}

/**
 * skolmaten.se (unofficial). Accepts what parents paste: the school page ("https://skolmaten.se/vasaskolan"), the old
 * RSS URL ("/vasaskolan/rss/weeks", now returns HTML) or just the slug, and uses the current endpoint
 * `https://skolmaten.se/api/4/rss/week/{slug}` (ADR-011). Fetched at most once a day per school.
 */
class SkolmatenRssProvider(private val fetcher: CachedFetcher, private val zone: TimeZone) : LunchMenuProvider {
    override fun supports(source: LunchSource) = source.provider == LunchProviderType.Skolmaten || source.provider == LunchProviderType.Rss

    override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? {
        val url = when (source.provider) {
            LunchProviderType.Skolmaten -> slugOf(source.reference)?.let { "https://skolmaten.se/api/4/rss/week/$it?locale=sv" }
            else -> source.reference.trim().takeIf { it.startsWith("https://") }
        } ?: return null
        val raw = fetcher.get("lunch:$url", url, maxAge = 20.hours, force = force, maxBytes = RssText.MAX_FEED_BYTES) {
            it.contains("<rss") || it.contains("<item")
        } ?: return null
        val name = if (source.provider == LunchProviderType.Skolmaten) "skolmaten.se" else url.substringAfter("://").substringBefore('/')
        return Sourced(RssLunchParser.parse(raw.value, zone, name), raw.fetchedAt, raw.stale, raw.error)
    }

    companion object {
        /**
         * "https://skolmaten.se/vasaskolan/rss/weeks" -> "vasaskolan"; "Vasaskolan" -> "vasaskolan";
         * "Söderskolan" -> "soderskolan" (skolmaten's slugs are ASCII, "söderskolan" answers 500).
         */
        fun slugOf(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val path = if ("skolmaten.se" in t) t.substringAfter("skolmaten.se").trim('/') else t
            val segments = path.split('/').filter { it.isNotBlank() && it !in setOf("api", "4", "rss", "week", "weeks", "day") }
            val slug = segments.firstOrNull()?.substringBefore('?')?.lowercase() ?: return null
            return slug.replace(' ', '-')
                .map { SLUG_LETTERS[it] ?: it }
                .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
                .joinToString("")
                .ifEmpty { null }
        }

        private val SLUG_LETTERS = mapOf('å' to 'a', 'ä' to 'a', 'ö' to 'o', 'é' to 'e', 'è' to 'e', 'ü' to 'u', 'æ' to 'a', 'ø' to 'o')
    }
}

/**
 * Matilda (Matilda Foodtech, formerly FoodIT), used by many municipalities and caterers. The menu page has an official
 * RSS feed for the current week. Parents paste the menu link ("https://menu.matildaplatform.com/meals/week/<id>_<name>");
 * the id before the underscore is the `distributorId` of
 * `https://menu.matildaplatform.com/rss?displayMode=week&distributorId=<id>&lang=sv` (ADR-053). Fetched at most once a day.
 */
class MatildaRssProvider(private val fetcher: CachedFetcher) : LunchMenuProvider {
    override fun supports(source: LunchSource) = source.provider == LunchProviderType.Matilda

    override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? {
        val id = distributorIdOf(source.reference) ?: return null
        val url = "https://menu.matildaplatform.com/rss?displayMode=week&distributorId=$id&lang=sv"
        val raw = fetcher.get("lunch:$url", url, maxAge = 20.hours, force = force, maxBytes = RssText.MAX_FEED_BYTES) {
            it.contains("<rss")
        } ?: return null
        return Sourced(MatildaLunchParser.parse(raw.value, SOURCE_NAME), raw.fetchedAt, raw.stale, raw.error)
    }

    companion object {
        const val SOURCE_NAME = "Matilda"
        private val hexRun = Regex("[0-9a-fA-F]+")

        /** The 24 character id in a menu link, an RSS link or on its own; null if there is none. */
        fun distributorIdOf(input: String): String? = hexRun.findAll(input.trim()).map { it.value }.firstOrNull { it.length == ID_LENGTH }
            ?.lowercase()

        private const val ID_LENGTH = 24
    }
}

/**
 * Matilda's RSS has no pubDate and one item per course and day: the description starts with the course ("Dagens",
 * "Tillbehör", "Vegetariskt") followed by the dishes joined with " & ". A new dish starts with a capital letter, so
 * "Pannbiff med lök & gräddsås" stays one dish while "Fredagsspecial & Gotlandslasagne" is two.
 */
object MatildaLunchParser {
    private val isoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val dishSplit = Regex("""\s+&\s+(?=\p{Lu})""")

    /** Course names that just mean "lunch" and need no label in front of the dish. */
    private val mainCourses = setOf("dagens", "dagens rätt", "dagens lunch", "lunch", "huvudrätt", "huvudrätter")

    fun parse(xml: String, sourceName: String): List<SchoolLunch> = RssText.elements(xml, "item").mapNotNull { body ->
        val date = dateOf(RssText.first(body, "guid")) ?: dateOf(RssText.first(body, "link")) ?: return@mapNotNull null
        val text = RssText.first(body, "description").orEmpty().removePrefix("<![CDATA[").removeSuffix("]]>")
        val lines = RssText.lines(text)
        if (lines.isEmpty()) return@mapNotNull null
        val course = lines.first().takeIf { lines.size > 1 }
        val dishes = (
            if (course ==
                null
            ) {
                lines
            } else {
                lines.drop(1)
            }
        ).flatMap { it.split(dishSplit) }.map { it.trim() }.filter { it.isNotEmpty() }
        val labelled = if (course == null || course.lowercase() in mainCourses) dishes else dishes.map { "$course: $it" }
        date to labelled
    }.groupBy({ it.first }, { it.second }).map { (date, courses) -> SchoolLunch(date, courses.flatten().distinct(), sourceName) }
        .filter { it.dishes.isNotEmpty() }
        .sortedBy { it.date }

    private fun dateOf(value: String?): LocalDate? {
        val m = value?.let { v -> isoDate.findAll(v.take(RssText.MAX_FIELD)).lastOrNull() } ?: return null
        val (y, mo, d) = m.destructured
        return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }
}

/** Minimal RSS 2.0 reader for lunch feeds: one item per school day, dishes in the description. */
object RssLunchParser {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun parse(xml: String, zone: TimeZone, sourceName: String): List<SchoolLunch> = RssText.elements(xml, "item").mapNotNull { body ->
        val date = RssText.first(body, "pubDate")?.let { parseRfc822Date(it.take(RssText.MAX_FIELD), zone) } ?: return@mapNotNull null
        val desc = RssText.first(body, "description").orEmpty()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
        val dishes = RssText.lines(desc)
        if (dishes.isEmpty()) null else SchoolLunch(date, dishes, sourceName)
    }.toList().sortedBy { it.date }

    /** "Mon, 05 Oct 2026 00:00:00 GMT" (midnight UTC means that school day). */
    fun parseRfc822Date(value: String, zone: TimeZone): LocalDate? {
        val m =
            Regex("""(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})(?:\s+(\d{2}):(\d{2}):(\d{2}))?\s*(GMT|UTC|Z|[+-]\d{4})?""").find(value)
                ?: return null
        val (d, mon, y) = m.destructured
        val month = months.indexOf(mon.replaceFirstChar { it.uppercase() }) + 1
        if (month == 0) return null
        val date = runCatching { LocalDate(y.toInt(), month, d.toInt()) }.getOrNull() ?: return null
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        // Feeds publish midnight UTC for the school day; only a real time-of-day should shift the date.
        return if (hour ==
            0
        ) {
            date
        } else {
            Instant.parse("${date}T${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}Z").toLocalDateTime(zone).date
        }
    }
}

/**
 * Reading lunch RSS (ADR-011, ADR-053). A custom feed's address is typed by someone in the family (or the other home), so its
 * content can be anything: elements and tags are found with plain index scans, which stay linear where a lazy regex such as
 * `<item>([\s\S]*?)</item>` goes quadratic on a feed that opens many items and closes none, and every text is cut to a
 * size no menu needs before a regex sees it.
 */
internal object RssText {
    /** A week's menu is a few kilobytes; a feed this large is not read at all. */
    const val MAX_FEED_BYTES = 1024L * 1024

    /** A date, guid or link. */
    const val MAX_FIELD = 500

    /** One day's description, and one dish in it. */
    private const val MAX_DESCRIPTION = 10_000
    private const val MAX_LINE = 300

    private val breaks = Regex("""<br\s*/?>|\n""", RegexOption.IGNORE_CASE)

    /** The text inside every `<tag>…</tag>` (attributes allowed) in [xml], in order. */
    fun elements(xml: String, tag: String): Sequence<String> = sequence {
        val open = "<$tag"
        val close = "</$tag>"
        var start = xml.indexOf(open)
        while (start >= 0) {
            val nameEnd = start + open.length
            // "<item>" or "<guid isPermaLink=…>", but not "<items>"; nothing more once an element is never closed.
            val isTag = nameEnd < xml.length && (xml[nameEnd] == '>' || xml[nameEnd].isWhitespace())
            val bodyStart = if (isTag) xml.indexOf('>', nameEnd) else -1
            val end = if (bodyStart < 0) -1 else xml.indexOf(close, bodyStart + 1)
            start = when {
                !isTag -> xml.indexOf(open, nameEnd)
                end < 0 -> -1
                else -> {
                    yield(xml.substring(bodyStart + 1, end))
                    xml.indexOf(open, end + close.length)
                }
            }
        }
    }

    fun first(xml: String, tag: String): String? = elements(xml, tag).firstOrNull()

    fun lines(description: String): List<String> = description.take(MAX_DESCRIPTION).split(breaks)
        .map { decodeEntities(stripTags(it)).trim().trimEnd(',').take(MAX_LINE) }
        .filter { it.isNotEmpty() }

    /** Drops every `<…>` in one pass; a `<` that nothing closes stays text. */
    private fun stripTags(s: String): String {
        if ('<' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val end = if (s[i] == '<') s.indexOf('>', i + 1) else -1
            when {
                // Nothing closes after here: the rest is text.
                s[i] == '<' && end < 0 -> {
                    out.append(s, i, s.length)
                    i = s.length
                }
                end > i + 1 -> i = end + 1
                else -> {
                    out.append(s[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    // &amp; last, so "&amp;lt;" becomes "&lt;" and not "<".
    private fun decodeEntities(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}

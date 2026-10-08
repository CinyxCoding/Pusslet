package se.pusslet.data.sources

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.SchoolLunch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A school lunch menu source. Implementations: skolmaten.se RSS, any RSS URL, manual entries. */
interface LunchMenuProvider {
    fun supports(source: LunchSource): Boolean

    suspend fun lunches(source: LunchSource, force: Boolean = false): Sourced<List<SchoolLunch>>?
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
        val raw =
            fetcher.get("lunch:$url", url, maxAge = 20.hours, force = force) { it.contains("<rss") || it.contains("<item") } ?: return null
        val name = if (source.provider == LunchProviderType.Skolmaten) "skolmaten.se" else url.substringAfter("://").substringBefore('/')
        return Sourced(RssLunchParser.parse(raw.value, zone, name), raw.fetchedAt, raw.stale, raw.error)
    }

    companion object {
        /** "https://skolmaten.se/vasaskolan/rss/weeks" -> "vasaskolan"; "Vasaskolan" -> "vasaskolan". */
        fun slugOf(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val path = if ("skolmaten.se" in t) t.substringAfter("skolmaten.se").trim('/') else t
            val segments = path.split('/').filter { it.isNotBlank() && it !in setOf("api", "4", "rss", "week", "weeks", "day") }
            val slug = segments.firstOrNull()?.substringBefore('?')?.lowercase() ?: return null
            return slug.replace(' ', '-').filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { null }
        }
    }
}

/** Minimal RSS 2.0 reader for lunch feeds: one item per school day, dishes in the description. */
object RssLunchParser {
    // [\s\S] instead of DOT_MATCHES_ALL, which is JVM-only.
    private val item = Regex("""<item>([\s\S]*?)</item>""")
    private val pubDate = Regex("""<pubDate>([\s\S]*?)</pubDate>""")
    private val description = Regex("""<description>([\s\S]*?)</description>""")
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun parse(xml: String, zone: TimeZone, sourceName: String): List<SchoolLunch> = item.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val date = pubDate.find(body)?.groupValues?.get(1)?.let { parseRfc822Date(it, zone) } ?: return@mapNotNull null
        val desc = description.find(body)?.groupValues?.get(1).orEmpty()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
        val dishes = desc.split(Regex("""<br\s*/?>|\n""", RegexOption.IGNORE_CASE))
            .map { decodeEntities(it.replace(Regex("<[^>]+>"), "")).trim().trimEnd(',') }
            .filter { it.isNotEmpty() }
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

    private fun decodeEntities(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}

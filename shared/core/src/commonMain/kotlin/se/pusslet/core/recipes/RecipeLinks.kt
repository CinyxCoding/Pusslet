package se.pusslet.core.recipes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A page that lists several recipes, such as a weekly menu: its name and the links to its recipes, in the page's order. */
data class RecipeList(val title: String?, val links: List<String>)

/**
 * Finds the recipes a weekly menu links to, so the whole menu can be imported from one link (ADR-038). Reads schema.org
 * `ItemList` JSON-LD (what sites publish for lists of recipes) and recept.se's weekly menus, whose list only exists in the
 * data the page hands its own script. Only links on the page's own site count, and a list longer than [MAX_LINKS] is a
 * category page rather than a menu, so it is left alone.
 */
object RecipeLinks {
    const val MAX_LINKS = 12

    private const val MAX_DEPTH = 6
    private const val RECEPT_SE = "recept.se"
    private val titledTypes = listOf("ItemList", "Article", "NewsArticle", "BlogPosting", "CollectionPage", "WebPage")
    private val weekAttribute = Regex("""<page-week\b[^>]*?\s:week\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val slug = Regex("[a-z0-9-]+")
    private val metaTitle = Regex("""<meta[^>]+property\s*=\s*["']og:title["'][^>]*content\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)

    fun extract(html: String, pageUrl: String): RecipeList? {
        val blocks = RecipeJsonLd.blocks(html).toList()
        val site = host(pageUrl)
        val links = (blocks.firstNotNullOfOrNull { itemList(it, pageUrl, 0) } ?: receptSeWeek(html, site).orEmpty())
            .map { it.substringBefore('#') }
            .filter { it != pageUrl && host(it) == site }
            .distinct()
        if (links.isEmpty() || links.size > MAX_LINKS) return null
        return RecipeList(title(blocks, html), links)
    }

    /** The links of the first ItemList, looking through `@graph`, `mainEntity` and arrays. */
    private fun itemList(element: JsonElement, pageUrl: String, depth: Int): List<String>? {
        if (depth > MAX_DEPTH) return null
        return when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { itemList(it, pageUrl, depth + 1) }
            is JsonObject -> if (isType(element["@type"], "ItemList")) {
                (element["itemListElement"] as? JsonArray)?.mapNotNull { link(it, pageUrl) }?.takeIf { it.isNotEmpty() }
            } else {
                listOf("@graph", "mainEntity").firstNotNullOfOrNull { key -> element[key]?.let { itemList(it, pageUrl, depth + 1) } }
            }
            else -> null
        }
    }

    /** A list entry: a bare link, a ListItem with `url` or `item`, or a Recipe with its `url`. */
    private fun link(entry: JsonElement, pageUrl: String): String? = when (entry) {
        is JsonPrimitive -> entry.contentOrNull
        is JsonObject -> string(entry["url"]) ?: when (val item = entry["item"]) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> string(item["url"]) ?: string(item["@id"])
            else -> null
        }
        else -> null
    }?.let { absolute(it.trim(), pageUrl) }

    /**
     * recept.se's weekly menus ("Lättlagade veckomenyer") are drawn in the browser: the list is the `:week` attribute of
     * `<page-week>`, HTML-escaped JSON whose `recipes` each have a `slug`, and a recipe's page is /recept/{slug}.
     */
    private fun receptSeWeek(html: String, site: String): List<String>? {
        if (site != RECEPT_SE) return null
        val raw = weekAttribute.find(html)?.groupValues?.get(1) ?: return null
        val week = RecipeJsonLd.parse(RecipeJsonLd.decodeEntities(raw)) as? JsonObject ?: return null
        return (week["recipes"] as? JsonArray)?.mapNotNull { r ->
            string((r as? JsonObject)?.get("slug"))?.takeIf { slug.matches(it) }?.let { "https://$RECEPT_SE/recept/$it" }
        }
    }

    /** The name of a page with several recipes on it, for the collection they are gathered in. */
    fun titleOf(html: String): String? = title(RecipeJsonLd.blocks(html).toList(), html)

    /** The menu's name: the list's or the article's headline, else the page title ("Veckomeny 44 - 2026"). */
    private fun title(blocks: List<JsonElement>, html: String): String? = blocks.firstNotNullOfOrNull { headline(it, 0) }
        ?: listOf(metaTitle, titleTag).firstNotNullOfOrNull { r ->
            r.find(html)?.groupValues?.get(1)?.let(RecipeJsonLd::clean)?.takeIf { it.isNotBlank() }
        }

    private fun headline(element: JsonElement, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        return when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { headline(it, depth + 1) }
            is JsonObject -> if (titledTypes.any { isType(element["@type"], it) }) {
                string(element["headline"]) ?: string(element["name"])
            } else {
                element["@graph"]?.let { headline(it, depth + 1) }
            }
            else -> null
        }
    }

    private fun isType(type: JsonElement?, name: String): Boolean = when (type) {
        is JsonPrimitive -> type.contentOrNull?.substringAfterLast('/') == name
        is JsonArray -> type.any { isType(it, name) }
        else -> false
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull?.let(RecipeJsonLd::clean)?.takeIf { it.isNotBlank() }

    private fun absolute(link: String, pageUrl: String): String? = when {
        link.startsWith("https://") || link.startsWith("http://") -> link
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> pageUrl.substringBefore("://") + "://" + pageUrl.substringAfter("://").substringBefore('/') + link
        else -> null
    }

    private fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase().removePrefix("www.")
}

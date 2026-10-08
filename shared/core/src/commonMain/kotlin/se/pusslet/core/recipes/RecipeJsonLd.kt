package se.pusslet.core.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import se.pusslet.core.ics.IcsParser
import se.pusslet.core.meals.DishKeywords
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag

/** A recipe read from a web page, before the user confirms it. */
data class ImportedRecipe(val recipe: Recipe, val warnings: Set<ImportWarning> = emptySet())

enum class ImportWarning { NoIngredients, NoSteps, ServingsGuessed }

/**
 * Reads schema.org/Recipe JSON-LD, which practically every Swedish recipe site publishes for search engines
 * (ICA, Arla, Köket, Coop, Mitt Kök …). Only the page the user chose is fetched; nothing is crawled.
 * Handles `@graph`, `@type` arrays, HowToStep/HowToSection instructions, ImageObject and yields like "4 portioner".
 */
object RecipeJsonLd {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val scriptTag = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE,
    )
    private const val DEFAULT_SERVINGS = 4

    fun extract(html: String, sourceUrl: String, id: String = ""): ImportedRecipe? {
        val node = scriptTag.findAll(html)
            .mapNotNull { m -> runCatching { json.parseToJsonElement(m.groupValues[1].trim()) }.getOrNull() }
            .firstNotNullOfOrNull { findRecipe(it) }
            ?: return null
        return fromNode(node, sourceUrl, id)
    }

    /** Depth-first search for an object whose @type is (or contains) "Recipe". */
    fun findRecipe(element: JsonElement, depth: Int = 0): JsonObject? {
        if (depth > MAX_DEPTH) return null
        return when (element) {
            is JsonArray -> element.firstNotNullOfOrNull { findRecipe(it, depth + 1) }
            is JsonObject -> {
                if (isRecipe(element["@type"])) {
                    element
                } else {
                    listOf("@graph", "mainEntity", "mainEntityOfPage", "itemListElement", "item")
                        .firstNotNullOfOrNull { key -> element[key]?.let { findRecipe(it, depth + 1) } }
                }
            }
            else -> null
        }
    }

    private fun isRecipe(type: JsonElement?): Boolean = when (type) {
        is JsonPrimitive -> type.contentOrNull?.substringAfterLast('/') == "Recipe"
        is JsonArray -> type.any { isRecipe(it) }
        else -> false
    }

    fun fromNode(node: JsonObject, sourceUrl: String, id: String = ""): ImportedRecipe {
        val warnings = mutableSetOf<ImportWarning>()
        val title = text(node["name"]) ?: text(node["headline"]) ?: ""
        val ingredients = strings(node["recipeIngredient"] ?: node["ingredients"]).map { IngredientParser.parse(it) }
        val steps = steps(node["recipeInstructions"])
        val servings = servings(node["recipeYield"]) ?: run {
            warnings += ImportWarning.ServingsGuessed
            DEFAULT_SERVINGS
        }
        if (ingredients.isEmpty()) warnings += ImportWarning.NoIngredients
        if (steps.isEmpty()) warnings += ImportWarning.NoSteps
        val total = minutes(node["totalTime"]) ?: sumOrNull(minutes(node["prepTime"]), minutes(node["cookTime"]))
        val keywords = (
            strings(node["keywords"]).flatMap { it.split(',') } + strings(node["recipeCategory"]) +
                strings(node["suitableForDiet"])
        ).map { it.trim().lowercase() }
        val main = DishKeywords.mainComponent((listOf(title) + ingredients.map { it.name }).joinToString(" "))
        val recipe = Recipe(
            id = id,
            title = title.trim(),
            servings = servings,
            ingredients = ingredients,
            steps = steps,
            tags = tags(keywords, main, total),
            prepMinutes = minutes(node["prepTime"]),
            totalMinutes = total,
            mainComponent = main,
            sourceUrl = sourceUrl,
            sourceName = text((node["publisher"] as? JsonObject)?.get("name")) ?: hostOf(sourceUrl),
            imageUrl = image(node["image"]),
        )
        return ImportedRecipe(recipe, warnings)
    }

    private fun tags(keywords: List<String>, main: MainComponent?, total: Int?): Set<RecipeTag> = buildSet {
        if (keywords.any { "vegan" in it }) add(RecipeTag.Vegan)
        if (keywords.any { "vegetari" in it } || main == MainComponent.Vegetarian) add(RecipeTag.Vegetarian)
        if (keywords.any { "barn" in it || "kid" in it }) add(RecipeTag.KidFriendly)
        if (keywords.any { "frys" in it }) add(RecipeTag.FreezerFriendly)
        if (keywords.any { "snabb" in it || "quick" in it } || (total != null && total <= QUICK_MINUTES)) add(RecipeTag.Quick)
        if (keywords.any { "helg" in it || "fest" in it }) add(RecipeTag.Weekend)
        if (keywords.any { "budget" in it || "billig" in it }) add(RecipeTag.Budget)
    }

    private fun steps(element: JsonElement?): List<String> = when (element) {
        null -> emptyList()
        is JsonPrimitive -> element.contentOrNull?.let { splitSteps(clean(it)) }.orEmpty()
        is JsonArray -> element.flatMap { steps(it) }
        is JsonObject -> {
            val type = text(element["@type"])
            when {
                type == "HowToSection" -> {
                    val heading = text(element["name"])
                    val inner = steps(element["itemListElement"])
                    if (heading != null && inner.isNotEmpty()) listOf("$heading:") + inner else inner
                }
                else -> listOfNotNull(text(element["text"]) ?: text(element["name"]))
            }
        }
    }

    /** A single text block with numbered or line-separated steps. */
    private fun splitSteps(text: String): List<String> =
        text.split(Regex("""\n+|(?<=[.!])\s+(?=\d+[.)]\s)""")).map {
            it.trim().replace(Regex("""^\d+[.)]\s*"""), "")
        }.filter { it.isNotEmpty() }

    private fun servings(element: JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.intOrNull ?: element.contentOrNull?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        is JsonArray -> element.firstNotNullOfOrNull { servings(it) }
        else -> null
    }?.takeIf { it in 1..MAX_SERVINGS }

    private fun minutes(element: JsonElement?): Int? =
        text(element)?.let { IcsParser.parseDuration(it) }?.inWholeMinutes?.toInt()?.takeIf { it > 0 }

    private fun image(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.firstNotNullOfOrNull { image(it) }
        is JsonObject -> text(element["url"]) ?: text(element["contentUrl"])
        else -> null
    }?.takeIf { it.startsWith("https://") }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.let(::clean)?.takeIf { it.isNotBlank() })
        is JsonArray -> element.flatMap { strings(it) }
        is JsonObject -> listOfNotNull(text(element["name"]) ?: text(element["text"]))
        else -> emptyList()
    }

    private fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let(::clean)?.takeIf { it.isNotBlank() }
        is JsonArray -> element.firstNotNullOfOrNull { text(it) }
        else -> null
    }

    /** Strips tags and decodes the HTML entities recipe sites leave in their JSON. */
    fun clean(
        raw: String,
    ): String = decodeEntities(raw.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), ""))
        .replace(Regex("""[ \t ]+"""), " ")
        .trim()

    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "auml" to "ä",
        "Auml" to "Ä",
        "ouml" to "ö",
        "Ouml" to "Ö",
        "aring" to "å",
        "Aring" to "Å",
        "eacute" to "é",
        "frac12" to "½",
        "frac14" to "¼",
        "frac34" to "¾",
        "ndash" to "–",
        "mdash" to "—",
        "deg" to "°",
    )

    private fun decodeEntities(s: String): String = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+\d*);""").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(HEX)?.let(::codePoint)
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint)
            else -> namedEntities[e]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = when {
        cp in 0..0xFFFF -> cp.toChar().toString()
        cp <= MAX_CODE_POINT -> {
            val v = cp - 0x10000
            charArrayOf(((v shr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
        else -> null
    }

    private fun sumOrNull(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    private const val MAX_DEPTH = 6
    private const val MAX_SERVINGS = 50
    private const val QUICK_MINUTES = 30
    private const val HEX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
}

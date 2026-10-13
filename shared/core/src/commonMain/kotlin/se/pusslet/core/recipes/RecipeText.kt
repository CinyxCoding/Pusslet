package se.pusslet.core.recipes

import se.pusslet.core.model.Ingredient
import se.pusslet.core.units.QuantityParser
import se.pusslet.core.units.Units

/** A recipe pasted as plain text, split into its parts for the editor (the user checks and adjusts). */
data class SplitRecipe(
    val title: String?,
    val servings: Int?,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
)

/**
 * Rule-based splitting of pasted recipe text when a page has no structured data (no AI, ADR-001):
 * headings ("Ingredienser", "Gör så här") decide the section when present; otherwise a line that starts with an amount
 * ("2 dl", "1 st", "½") is an ingredient and the rest are steps. Numbered steps ("1.", "2)") lose their numbers.
 */
object RecipeText {
    private val ingredientHeadings = listOf("ingredienser", "ingredients", "du behöver", "det här behöver du")
    private val stepHeadings = listOf("gör så här", "så här gör du", "instruktioner", "tillagning", "instructions", "method")
    private val servingsPattern = Regex("""(\d+)\s*(?:portioner|portion|port\.|personer|pers\.|servings)""", RegexOption.IGNORE_CASE)
    private val stepNumber = Regex("""^\s*(?:steg\s*)?\d+\s*[.):]\s+""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^[\s•·*\-–]+""")

    private enum class Section { Unknown, Ingredients, Steps }

    fun split(text: String): SplitRecipe {
        val lines = text.lines().map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotEmpty() }
        var section = Section.Unknown
        var title: String? = null
        var servings: Int? = null
        var group: String? = null
        val ingredients = mutableListOf<Ingredient>()
        val steps = mutableListOf<String>()
        for ((index, line) in lines.withIndex()) {
            val lower = line.lowercase().trimEnd(':')
            when {
                ingredientHeadings.any { lower.startsWith(it) } -> {
                    section = Section.Ingredients
                    servings = servings ?: servingsIn(line)
                }
                stepHeadings.any { lower.startsWith(it) } -> section = Section.Steps
                servings == null && servingsIn(line) != null && line.length < SHORT_LINE -> servings = servingsIn(line)
                index == 0 && !looksLikeIngredient(line) && line.length < SHORT_LINE -> title = line
                section == Section.Ingredients && line.endsWith(':') -> group = line.trimEnd(':').trim()
                section == Section.Ingredients -> ingredients += IngredientParser.parse(line, group)
                section == Section.Steps -> steps += cleanStep(line)
                looksLikeIngredient(line) -> ingredients += IngredientParser.parse(line)
                else -> steps += cleanStep(line)
            }
        }
        return SplitRecipe(title, servings, ingredients, steps.filter { it.isNotEmpty() })
    }

    /** One editable line per ingredient ("2 ½ dl grädde, vispad"); groups become "Sås:" headings. */
    fun formatIngredients(ingredients: List<Ingredient>): String = buildString {
        var group: String? = null
        for (ing in ingredients) {
            if (ing.group != group) {
                group = ing.group
                if (group != null) {
                    if (isNotEmpty()) append('\n')
                    append(group).append(":\n")
                }
            }
            append(line(ing)).append('\n')
        }
    }.trimEnd()

    fun line(ing: Ingredient): String {
        val head = listOfNotNull(ing.amount?.let { Units.format(it) }, ing.name.trim()).joinToString(" ")
        return if (ing.note.isBlank()) head else "$head, ${ing.note.trim()}"
    }

    /** The editor's text back to ingredients: a line ending in ":" starts a group. */
    fun parseIngredients(text: String): List<Ingredient> {
        var group: String? = null
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            if (line.endsWith(':')) {
                group = line.trimEnd(':').trim().ifEmpty { null }
                null
            } else {
                IngredientParser.parse(line, group)
            }
        }
    }

    /** One step per line; numbering is shown by the app, so typed numbers are removed. */
    fun parseSteps(text: String): List<String> = text.lines().map(::cleanStep).filter { it.isNotEmpty() }

    private fun servingsIn(line: String): Int? = servingsPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** "2 dl grädde", "1 gul lök", "½ tsk salt" – short and starting with an amount. */
    private fun looksLikeIngredient(line: String): Boolean {
        val clean = line.replace(bullet, "")
        if (clean.length > INGREDIENT_MAX || stepNumber.containsMatchIn(clean)) return false
        val prefix = QuantityParser.parsePrefix(clean)
        return prefix.quantity != null && prefix.rest.isNotBlank()
    }

    private fun cleanStep(line: String): String = line.replace(bullet, "").replace(stepNumber, "").trim()

    private const val SHORT_LINE = 60
    private const val INGREDIENT_MAX = 70
}

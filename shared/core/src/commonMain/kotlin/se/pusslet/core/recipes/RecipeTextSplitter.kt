package se.pusslet.core.recipes

import se.pusslet.core.model.Ingredient
import se.pusslet.core.units.QuantityParser

/** A pasted recipe split into parts, for the user to check and adjust before saving. */
data class SplitRecipe(
    val title: String,
    val servings: Int?,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
)

/**
 * Fallback when a page has no structured data: the user pastes the recipe text and it is split by rules.
 * Uses headings when present ("Ingredienser", "Gör så här"), otherwise looks at each line: lines that start with an
 * amount ("2 dl grädde") or are short lists of words are ingredients, numbered or full sentences are steps.
 */
object RecipeTextSplitter {
    private enum class Section { Unknown, Ingredients, Steps }

    private val ingredientHeadings = Regex(
        """^(ingredienser|ingredients|du behöver|det här behöver du|till \d+ portioner|handla)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    private val stepHeadings = Regex(
        """^(gör så här|så gör du|så här gör du|instruktioner|tillagning|tillvägagångssätt|beskrivning|steg|""" +
            """method|instructions|directions)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    private val servingsPattern = Regex(
        """(?:(\d+)\s*(?:portioner|port\.?|pers(?:oner)?\.?|servings)|(?:portioner|serves|servings)\s*:?\s*(\d+))""",
        RegexOption.IGNORE_CASE,
    )
    private val stepNumber = Regex("""^(?:steg\s*)?\d{1,2}\s*[.)]\s*(?=\p{Lu}|\p{L})""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^[•·*\-–]\s*""")
    private const val MAX_INGREDIENT_CHARS = 70
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_SHORT_WORDS = 5

    fun split(text: String): SplitRecipe {
        val lines = text.lines().map { it.replace(' ', ' ').trim() }
        var title = ""
        var servings: Int? = null
        var section = Section.Unknown
        var group: String? = null
        val ingredients = mutableListOf<Ingredient>()
        val steps = mutableListOf<String>()

        for (raw in lines) {
            if (raw.isEmpty()) continue
            val line = raw.trimEnd()
            if (servings ==
                null
            ) {
                servingsPattern.find(line)?.let { m -> servings = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
            }
            when {
                ingredientHeadings.matches(line) && line.length < MAX_TITLE_CHARS -> {
                    section = Section.Ingredients
                    group = null
                }
                stepHeadings.matches(line) && line.length < MAX_TITLE_CHARS -> section = Section.Steps
                isServingsOnly(line) -> Unit
                title.isEmpty() && ingredients.isEmpty() && steps.isEmpty() && section == Section.Unknown && isTitleLike(line) ->
                    title =
                        line
                section == Section.Ingredients && line.endsWith(':') && line.length < MAX_TITLE_CHARS -> group = line.dropLast(1).trim()
                section == Section.Ingredients -> if (isIngredientLike(line)) {
                    ingredients += IngredientParser.parse(line, group)
                } else {
                    // A long sentence after the ingredient list: the steps have started without a heading.
                    section = Section.Steps
                    steps += cleanStep(line)
                }
                section == Section.Steps -> appendStep(steps, line)
                isIngredientLike(line) && steps.isEmpty() -> ingredients += IngredientParser.parse(line, group)
                else -> appendStep(steps, line)
            }
        }
        return SplitRecipe(title, servings, ingredients, steps)
    }

    private fun appendStep(steps: MutableList<String>, line: String) {
        val clean = cleanStep(line)
        val numbered = stepNumber.containsMatchIn(line)
        // Lines that continue a sentence (lower-case start, previous step not finished) are glued together.
        val previous = steps.lastOrNull()
        if (!numbered && previous != null && clean.first().isLowerCase() && !previous.endsWith('.')) {
            steps[steps.lastIndex] = "$previous $clean"
        } else {
            steps += clean
        }
    }

    private fun cleanStep(line: String) = line.replace(stepNumber, "").replace(bullet, "").trim()

    private fun isServingsOnly(line: String) = servingsPattern.matches(line.trim().trimEnd('.'))

    private fun isTitleLike(line: String): Boolean =
        line.length <= MAX_TITLE_CHARS && !line.endsWith('.') && !stepNumber.containsMatchIn(line) &&
            QuantityParser.parsePrefix(line).quantity == null

    fun isIngredientLike(line: String): Boolean {
        val text = line.replace(bullet, "")
        if (text.length > MAX_INGREDIENT_CHARS || stepNumber.containsMatchIn(text)) return false
        if (QuantityParser.parsePrefix(text).quantity != null) return true
        val words = text.split(' ').filter { it.isNotBlank() }
        return words.size <= MAX_SHORT_WORDS && !text.endsWith('.') && !text.endsWith(':')
    }
}

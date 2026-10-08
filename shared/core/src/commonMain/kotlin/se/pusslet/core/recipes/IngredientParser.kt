package se.pusslet.core.recipes

import se.pusslet.core.model.Ingredient
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.QuantityParser

/**
 * Reads one ingredient line as written in Swedish recipes:
 * "2 dl vispgrädde", "1 stor gul lök, finhackad", "400 g kycklingfilé (ca 3 st)", "salt och peppar",
 * "1 burk krossade tomater à 400 g", "½ tsk chiliflakes eller efter smak".
 */
object IngredientParser {
    private val bullet = Regex("""^[\s•·*\-–]+""")
    private val parenthesis = Regex("""\(([^)]*)\)""")
    private val notePrefixes = listOf(" eller ", " till ", " gärna ", " efter smak", " à ", " a ")
    private val optionalHints = listOf("efter smak", "till servering", "valfritt", "om du vill", "kan uteslutas", "eventuellt")

    fun parse(line: String, group: String? = null): Ingredient {
        val clean = line.replace(bullet, "").replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
        val notes = mutableListOf<String>()
        var text = clean.replace(parenthesis) { m ->
            notes += m.groupValues[1].trim()
            ""
        }.replace(Regex("""\s+"""), " ").trim()

        val prefix = QuantityParser.parsePrefix(text)
        var quantity = prefix.quantity
        text = prefix.rest

        // "1 burk krossade tomater" -> unit burk parsed already; "1 st gurka" -> st.
        val comma = text.indexOf(',')
        if (comma >= 0) {
            notes.add(0, text.substring(comma + 1).trim())
            text = text.substring(0, comma).trim()
        }
        for (p in notePrefixes) {
            val i = text.lowercase().indexOf(p)
            if (i > 0) {
                notes.add(0, text.substring(i).trim())
                text = text.substring(0, i).trim()
            }
        }
        val descriptors = ShoppingNames.descriptorsIn(text)
        val name = text.split(' ').filter { it.lowercase() !in ShoppingNames.descriptors }.joinToString(" ").trim()
        // A bare count ("2 gula lökar") is pieces.
        if (quantity != null && quantity.unit == null && name.isNotEmpty()) quantity = quantity.copy(unit = MeasureUnit.St)

        val allNotes = (descriptors + notes).map { it.trim(',', ' ') }.filter { it.isNotEmpty() }
        val lowerLine = clean.lowercase()
        val optional = ShoppingNames.isStaple(name) || optionalHints.any { it in lowerLine } && quantity == null
        return Ingredient(
            name = name.ifEmpty { clean },
            quantity = quantity?.value,
            unit = quantity?.unit,
            quantityMax = quantity?.max,
            note = allNotes.joinToString(", "),
            group = group,
            optional = optional,
        )
    }
}

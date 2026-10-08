package se.pusslet.core.shopping

import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.units.QuantityParser

/**
 * The quick-add field at the top of a shopping list: everything typed there is groceries, so no intent detection is
 * needed. "mjölk, 2 gurka och pasta" -> three items; "1,5 l mjölk" keeps its decimal comma.
 */
object ShoppingInput {
    private val separators = Regex("""(?<!\d),|,(?!\d)|;|\n| och | samt | & | \+ """)
    private val prefix = Regex("""^(köp|köpa|handla)\s*:?\s+""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ParsedItem> = text.replace(prefix, "")
        .split(separators)
        .map { it.trim().trimEnd('.').trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            val q = QuantityParser.parsePrefix(part)
            val name = q.rest.trim().ifEmpty { part }
            ParsedItem(name.replaceFirstChar { it.uppercase() }, q.quantity.takeIf { q.rest.isNotBlank() })
        }
}

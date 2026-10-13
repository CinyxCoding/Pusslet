package se.pusslet.core.shopping

import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.QuantityParser

/**
 * The add field on the shopping list. Everything typed there is groceries, so no sentence parsing is needed:
 * "2 l mjölk, bröd och gurka x2" becomes three items. Amounts may come first ("2 dl grädde") or last with a unit
 * ("mjölk 2 l", "gurka x2"); a bare trailing number stays in the name ("Blöjor storlek 5").
 */
object ShoppingInput {
    private val separators = Regex("""(?<!\d),|,(?!\d)|;|\n| och | samt | & | \+ """)
    private val trailingTimes = Regex("""\s+x\s*(\d+)$""", RegexOption.IGNORE_CASE)
    private val trailingAmount = Regex("""\s+(\d+(?:[.,]\d+)?)\s*([\p{L}.]+)$""")

    fun parse(text: String): List<ParsedItem> = text
        .split(separators)
        .map { it.trim().trimEnd('.').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull(::parseOne)

    private fun parseOne(part: String): ParsedItem? {
        val prefix = QuantityParser.parsePrefix(part)
        if (prefix.quantity != null && prefix.rest.isNotBlank()) return item(prefix.rest, prefix.quantity)
        trailingTimes.find(part)?.let { m ->
            val name = part.substring(0, m.range.first)
            return item(name, Quantity(m.groupValues[1].toDouble(), MeasureUnit.St))
        }
        trailingAmount.find(part)?.let { m ->
            val unit = MeasureUnit.parse(m.groupValues[2])
            val value = QuantityParser.parseNumber(m.groupValues[1])
            if (unit != null && value != null) return item(part.substring(0, m.range.first), Quantity(value, unit))
        }
        return item(part, null)
    }

    private fun item(name: String, quantity: Quantity?): ParsedItem? {
        val clean = name.trim().trimEnd(',', '.').trim()
        if (clean.isEmpty()) return null
        return ParsedItem(clean.replaceFirstChar { it.uppercase() }, quantity)
    }

    /**
     * Adding something that is already on the list should not create a second line: "mjölk" twice stays one line,
     * "1 l mjölk" + "2 l mjölk" becomes 3 l. Returns the updated existing item, or null when a new line is needed
     * (nothing matches, or the amounts cannot be added up, like "2 st" + "1 kg").
     * A matching line that is already ticked off is brought back with the new amount ("köp mer").
     */
    fun mergeInto(existing: List<ShoppingItem>, parsed: ParsedItem): ShoppingItem? {
        val key = ShoppingNames.canonical(parsed.name)
        val candidates = existing.filter { ShoppingNames.canonical(it.name) == key }
        val open = candidates.firstOrNull { !it.checked }
        if (open == null) {
            val bought = candidates.firstOrNull() ?: return null
            return bought.copy(
                checked = false,
                checkedAt = null,
                checkedBy = null,
                quantity = parsed.quantity?.value,
                unit = parsed.quantity?.unit,
            )
        }
        val add = parsed.quantity ?: return open
        val current = open.amount ?: return open.copy(quantity = add.value, unit = add.unit)
        // "2 gurka" and "1 st gurka" are the same kind of amount.
        val currentUnit = current.unit ?: MeasureUnit.St
        val addUnit = add.unit ?: MeasureUnit.St
        val converted = Quantity(add.value, addUnit).convertTo(currentUnit) ?: return null
        return open.copy(quantity = current.value + converted.value, unit = current.unit ?: add.unit)
    }
}

package se.pusslet.core.units

/** Result of reading an amount from the start of a text: "2 dl grädde" -> (2 dl, "grädde"). */
data class QuantityPrefix(val quantity: Quantity?, val rest: String)

/**
 * Reads Swedish amounts: "2", "2,5", "1/2", "½", "1 ½", "1½", "2-3", "ca 2", "en halv", "ett par", "tre".
 * Units may follow ("2 dl", "500g", "1 msk"). Text without an amount returns quantity = null.
 */
object QuantityParser {
    private val unicodeFractions = mapOf('¼' to 0.25, '½' to 0.5, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125)

    private val numberWords = mapOf(
        "en" to 1.0,
        "ett" to 1.0,
        "två" to 2.0,
        "tva" to 2.0,
        "tre" to 3.0,
        "fyra" to 4.0,
        "fem" to 5.0,
        "sex" to 6.0,
        "sju" to 7.0,
        "åtta" to 8.0,
        "nio" to 9.0,
        "tio" to 10.0,
        "elva" to 11.0,
        "tolv" to 12.0,
        "ett par" to 2.0,
        "några" to 3.0,
        "en halv" to 0.5,
        "ett halvt" to 0.5,
        "halv" to 0.5,
        "halvt" to 0.5,
        "one" to 1.0,
        "two" to 2.0,
        "three" to 3.0,
        "a" to 1.0,
        "an" to 1.0,
        "half" to 0.5,
    )

    private val approx = Regex("""^(ca\.?|c:a|cirka|ungefär|about|approx\.?)\s+""", RegexOption.IGNORE_CASE)

    // number: 1 | 1,5 | 1.5 | 1/2 | 1 1/2 | 1½ | ½
    private const val NUM = """(\d+(?:[.,]\d+)?(?:\s*[¼½¾⅓⅔⅛])?|\d+\s+\d+/\d+|\d+/\d+|[¼½¾⅓⅔⅛])"""
    private val numberPattern = Regex("""^$NUM(?:\s*[-–]\s*$NUM)?""")

    fun parsePrefix(input: String): QuantityPrefix {
        var text = input.trim()
        text = text.replace(approx, "")
        val numMatch = numberPattern.find(text)
        var value: Double? = null
        var max: Double? = null
        if (numMatch != null) {
            value = parseNumber(numMatch.groupValues[1])
            max = numMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { parseNumber(it) }
            text = text.substring(numMatch.range.last + 1).trimStart()
        } else {
            val lower = text.lowercase()
            val word = numberWords.keys.sortedByDescending { it.length }.firstOrNull { w ->
                lower.startsWith("$w ") && w !in setOf("a", "an")
            }
            if (word != null) {
                value = numberWords.getValue(word)
                text = text.substring(word.length).trimStart()
            }
        }
        if (value == null) return QuantityPrefix(null, input.trim())

        // Unit directly after the number: "500g", "2 dl", "1 msk."
        val unitMatch = Regex("""^([A-Za-zåäöÅÄÖ]+\.?)(?=\s|$|,)""").find(text)
        var unit: MeasureUnit? = null
        if (unitMatch != null) {
            unit = MeasureUnit.parse(unitMatch.groupValues[1])
            if (unit != null) text = text.substring(unitMatch.range.last + 1).trimStart()
        }
        return QuantityPrefix(Quantity(value, unit, max), text.trimStart(',', ' '))
    }

    fun parseNumber(token: String): Double? {
        val t = token.trim()
        if (t.length == 1 && t[0] in unicodeFractions) return unicodeFractions[t[0]]
        val mixedUnicode = Regex("""^(\d+)\s*([¼½¾⅓⅔⅛])$""").matchEntire(t)
        if (mixedUnicode != null) {
            return mixedUnicode.groupValues[1].toDouble() + (unicodeFractions[mixedUnicode.groupValues[2][0]] ?: 0.0)
        }
        val mixed = Regex("""^(\d+)\s+(\d+)/(\d+)$""").matchEntire(t)
        if (mixed != null) {
            val (w, n, d) = mixed.destructured
            return if (d.toDouble() == 0.0) null else w.toDouble() + n.toDouble() / d.toDouble()
        }
        val fraction = Regex("""^(\d+)/(\d+)$""").matchEntire(t)
        if (fraction != null) {
            val (n, d) = fraction.destructured
            return if (d.toDouble() == 0.0) null else n.toDouble() / d.toDouble()
        }
        return t.replace(',', '.').toDoubleOrNull()
    }
}

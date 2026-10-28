package se.pusslet.core.util

/**
 * Alphabetical order the Swedish way: case does not matter and the alphabet ends … x y z å ä ö. Sorting on the plain
 * string puts Ä before Å (by code point) and every capital before every small letter. Common code has no Collator.
 */
object SwedishOrder {
    private val letters = mapOf(
        'å' to '{',
        'ä' to '|',
        'æ' to '|',
        'ö' to '}',
        'ø' to '}',
        'é' to 'e',
        'è' to 'e',
        'ü' to 'y',
    )

    /** A key that sorts like the Swedish alphabet ("Ål" < "Ägg" < "Ört"). */
    fun key(s: String): String = s.lowercase().map { letters[it] ?: it }.joinToString("")

    val comparator: Comparator<String> = compareBy(::key)
}

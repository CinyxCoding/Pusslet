package se.pusslet.core.search

/**
 * Plain local search over what the family has written down (events, recipes, to-dos, shopping items, contacts).
 * Rules, not AI (ADR-001): every word of the query must start a word in the text ("tand" finds "Tandläkare",
 * "läkare" finds it too because Swedish compounds are matched inside words from the third letter), case is ignored,
 * and hits in the title rank above hits in notes.
 *
 * Å, ä and ö are letters of their own (ADR-052): "ost" finds "Ost" before "Östron". Only when a word has no such hit is it
 * tried again with the dots folded away, so "mjolk" typed on a keyboard without them still finds "Mjölk", lower down.
 */
object TextSearch {
    /** Lowercase; everything that is not a letter or digit becomes a space. Å, ä and ö stay. */
    fun normalize(text: String): String = buildString(text.length) {
        for (c in text.lowercase()) append(if (c.isLetterOrDigit()) c else ' ')
    }

    /** [normalize], then å/ä→a, ö→o, é→e …: for the second try. */
    fun fold(text: String): String = buildString(text.length) {
        for (c in normalize(text)) {
            append(
                when (c) {
                    'å', 'ä', 'à', 'á', 'â', 'ã', 'æ' -> 'a'
                    'ö', 'ò', 'ó', 'ô', 'õ', 'ø' -> 'o'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'ü', 'ú', 'ù', 'û' -> 'u'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ç' -> 'c'
                    'ñ' -> 'n'
                    else -> c
                },
            )
        }
    }

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * 0 = no match. Otherwise higher is better: each query word scores by where it matched (title start > title word >
     * inside a title word > other fields), and a match only found with the dots folded away scores less.
     */
    fun score(query: List<String>, title: String, other: List<String> = emptyList()): Int {
        if (query.isEmpty()) return 0
        val titleWords = words(title)
        val otherWords = other.flatMap { words(it) }
        val foldedTitle = titleWords.map { fold(it) }
        val foldedOther = otherWords.map { fold(it) }
        var total = 0
        for (q in query) {
            val exact = wordScore(q, titleWords, otherWords)
            val s = if (exact > 0) exact else wordScore(fold(q), foldedTitle, foldedOther) / FOLDED_DIVISOR
            if (s == 0) return 0
            total += s
        }
        return total
    }

    private fun wordScore(q: String, titleWords: List<String>, otherWords: List<String>): Int = when {
        titleWords.firstOrNull()?.startsWith(q) == true -> TITLE_START
        titleWords.any { it.startsWith(q) } -> TITLE_WORD
        q.length >= INFIX_MIN && titleWords.any { q in it } -> TITLE_INSIDE
        otherWords.any { it.startsWith(q) } -> OTHER_WORD
        q.length >= INFIX_MIN && otherWords.any { q in it } -> OTHER_INSIDE
        else -> 0
    }

    private const val INFIX_MIN = 3
    private const val TITLE_START = 100
    private const val TITLE_WORD = 80
    private const val TITLE_INSIDE = 60
    private const val OTHER_WORD = 30
    private const val OTHER_INSIDE = 20
    private const val FOLDED_DIVISOR = 2
}

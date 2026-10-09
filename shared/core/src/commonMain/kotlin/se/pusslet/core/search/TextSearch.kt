package se.pusslet.core.search

/**
 * Plain local search over what the family has written down (events, recipes, to-dos, shopping items, contacts).
 * Rules, not AI (ADR-001): every word of the query must start a word in the text ("tand" finds "Tandläkare",
 * "läk" finds "tandläkare" too because Swedish compounds are matched inside words from the third letter), case and
 * diacritics are ignored so "lakare" finds "läkare", and hits in the title rank above hits in notes.
 */
object TextSearch {
    /** Lowercase, å/ä→a, ö→o, é→e …; everything that is not a letter or digit becomes a space. */
    fun normalize(text: String): String = buildString(text.length) {
        for (c in text.lowercase()) {
            append(
                when (c) {
                    'å', 'ä', 'à', 'á', 'â', 'ã' -> 'a'
                    'ö', 'ò', 'ó', 'ô', 'õ', 'ø' -> 'o'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'ü', 'ú', 'ù', 'û' -> 'u'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ç' -> 'c'
                    'ñ' -> 'n'
                    else -> if (c.isLetterOrDigit()) c else ' '
                },
            )
        }
    }

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * 0 = no match. Otherwise higher is better: each query word scores by where it matched (title start > title word >
     * inside a title word > other fields).
     */
    fun score(query: List<String>, title: String, other: List<String> = emptyList()): Int {
        if (query.isEmpty()) return 0
        val titleWords = words(title)
        val otherWords = other.flatMap { words(it) }
        var total = 0
        for (q in query) {
            val s = when {
                titleWords.firstOrNull()?.startsWith(q) == true -> TITLE_START
                titleWords.any { it.startsWith(q) } -> TITLE_WORD
                q.length >= INFIX_MIN && titleWords.any { q in it } -> TITLE_INSIDE
                otherWords.any { it.startsWith(q) } -> OTHER_WORD
                q.length >= INFIX_MIN && otherWords.any { q in it } -> OTHER_INSIDE
                else -> return 0
            }
            total += s
        }
        return total
    }

    private const val INFIX_MIN = 3
    private const val TITLE_START = 100
    private const val TITLE_WORD = 80
    private const val TITLE_INSIDE = 60
    private const val OTHER_WORD = 30
    private const val OTHER_INSIDE = 20
}

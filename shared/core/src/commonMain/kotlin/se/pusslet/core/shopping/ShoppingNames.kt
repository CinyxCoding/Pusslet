package se.pusslet.core.shopping

/**
 * Turns ingredient and list names into one key per grocery item, so "2 gula lökar" and "1 gul lök, hackad" end up on
 * the same line. Deliberately conservative: a small table of Swedish plurals and size words, nothing clever.
 */
object ShoppingNames {
    /** Size/state words that describe the piece, not the product. They move to the note. */
    val descriptors = setOf(
        "stor",
        "stora",
        "stort",
        "liten",
        "litet",
        "små",
        "lilla",
        "medelstor",
        "medelstora",
        "mellanstor",
        "mellanstora",
        "färsk",
        "färska",
        "färskt",
        "hel",
        "hela",
        "helt",
        "rumstempererad",
        "rumstempererat",
        "rumstempererade",
        "kall",
        "kallt",
        "kalla",
        "mjuk",
        "mjukt",
        "mjuka",
        "mogen",
        "moget",
        "mogna",
    )

    /** Pantry basics that recipes mention but nobody needs on the list (shown as "har du hemma?" instead). */
    val staples = setOf(
        "salt",
        "flingsalt",
        "havssalt",
        "peppar",
        "svartpeppar",
        "vitpeppar",
        "nymalen svartpeppar",
        "salt och peppar",
        "vatten",
        "kallt vatten",
        "varmt vatten",
        "kokande vatten",
        "is",
    )

    private val plurals = mapOf(
        "lökar" to "lök",
        "gula lökar" to "gul lök",
        "röda lökar" to "röd lök",
        "rödlökar" to "rödlök",
        "schalottenlökar" to "schalottenlök",
        "purjolökar" to "purjolök",
        "vårlökar" to "vårlök",
        "tomater" to "tomat",
        "körsbärstomater" to "körsbärstomat",
        "morötter" to "morot",
        "potatisar" to "potatis",
        "paprikor" to "paprika",
        "röda paprikor" to "röd paprika",
        "gurkor" to "gurka",
        "citroner" to "citron",
        "limefrukter" to "lime",
        "apelsiner" to "apelsin",
        "äpplen" to "äpple",
        "päron" to "päron",
        "bananer" to "banan",
        "avokador" to "avokado",
        "avocados" to "avokado",
        "zucchinis" to "zucchini",
        "squasher" to "squash",
        "auberginer" to "aubergine",
        "champinjoner" to "champinjon",
        "svampar" to "svamp",
        "chilifrukter" to "chili",
        "vitlöksklyftor" to "vitlöksklyfta",
        "klyftor vitlök" to "vitlöksklyfta",
        "ägg" to "ägg",
        "äggulor" to "äggula",
        "äggvitor" to "äggvita",
        "tortillas" to "tortilla",
        "tortillabröd" to "tortillabröd",
        "hamburgerbröd" to "hamburgerbröd",
        "korvar" to "korv",
        "fiskpinnar" to "fiskpinnar",
        "kycklingfiléer" to "kycklingfilé",
        "kycklinglår" to "kycklinglår",
        "laxfiléer" to "laxfilé",
        "torskfiléer" to "torskfilé",
        "rödbetor" to "rödbeta",
        "palsternackor" to "palsternacka",
        "sötpotatisar" to "sötpotatis",
        "selleristjälkar" to "selleristjälk",
        "lagerblad" to "lagerblad",
    )

    /** Lowercase, descriptors removed, plural -> singular. */
    fun canonical(name: String): String {
        val words = name.lowercase()
            .replace(Regex("""[^\p{L}\d%\s-]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in descriptors }
        val joined = words.joinToString(" ")
        plurals[joined]?.let { return it }
        // Plural of the last word only: "gula lökar" is covered above, "hackade tomater" -> "hackade tomat".
        val last = words.lastOrNull() ?: return joined
        val singular = plurals[last] ?: return joined
        return (words.dropLast(1) + singular).joinToString(" ")
    }

    /** Descriptor words found in [name], kept as a note ("stor"). */
    fun descriptorsIn(name: String): List<String> = name.lowercase().split(Regex("""\s+""")).filter { it in descriptors }

    fun isStaple(name: String): Boolean = canonical(name) in staples || name.trim().lowercase() in staples
}

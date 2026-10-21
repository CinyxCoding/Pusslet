package se.pusslet.core.meals

import se.pusslet.core.model.MainComponent

/**
 * "Helst inte samma som skollunchen": finds the main food words of a dish so lunch and dinner can be compared.
 * Rule based: a keyword list of typical Swedish dishes and proteins, matched as word stems.
 */
object DishKeywords {
    /** stem -> canonical key. Longer stems are checked first. */
    private val stems: List<Pair<String, String>> = listOf(
        "köttbull" to "köttbullar",
        "vegobull" to "köttbullar",
        "fiskpinn" to "fisk",
        "fiskgratäng" to "fisk",
        "fiskbull" to "fisk",
        "sill" to "fisk",
        "strömming" to "fisk",
        "makrill" to "fisk",
        "tonfisk" to "fisk",
        "kolja" to "fisk",
        "spätta" to "fisk",
        "gös" to "fisk",
        "pangasius" to "fisk",
        "mussl" to "skaldjur",
        "kalkon" to "kalkon",
        "pannbiff" to "köttfärs",
        "järpar" to "köttfärs",
        "kebab" to "köttfärs",
        "chorizo" to "korv",
        "salsiccia" to "korv",
        "bacon" to "fläsk",
        "skink" to "fläsk",
        "kassler" to "fläsk",
        "karré" to "fläsk",
        "revben" to "fläsk",
        "carbonara" to "fläsk",
        "gyros" to "fläsk",
        "kalops" to "biff",
        "högrev" to "biff",
        "entrecote" to "biff",
        "oxfilé" to "biff",
        "nötkött" to "biff",
        "renskav" to "biff",
        "lamm" to "biff",
        "falukorv" to "korv",
        "korv" to "korv",
        "taco" to "tacos",
        "tortilla" to "tacos",
        "lasagne" to "lasagne",
        "spaghetti" to "pasta",
        "pasta" to "pasta",
        "makaron" to "pasta",
        "penne" to "pasta",
        "nudl" to "nudlar",
        "pannkak" to "pannkakor",
        "plätt" to "pannkakor",
        "pizza" to "pizza",
        "hamburgare" to "hamburgare",
        "burgare" to "hamburgare",
        "kyckling" to "kyckling",
        "lax" to "lax",
        "torsk" to "fisk",
        "sej" to "fisk",
        "fisk" to "fisk",
        "räk" to "skaldjur",
        "ärtsoppa" to "ärtsoppa",
        "soppa" to "soppa",
        "gryta" to "gryta",
        "pytt" to "pytt",
        "gratäng" to "gratäng",
        "risotto" to "risotto",
        "curry" to "curry",
        "chili" to "chili",
        "köttfärs" to "köttfärs",
        "blodpudding" to "blodpudding",
        "kåldolm" to "kåldolmar",
        "biff" to "biff",
        "schnitzel" to "schnitzel",
        "fläsk" to "fläsk",
        "omelett" to "ägg",
        "äggröra" to "ägg",
        "falafel" to "falafel",
        "halloumi" to "halloumi",
        "gröt" to "gröt",
    ).sortedByDescending { it.first.length }

    fun of(text: String): Set<String> {
        val lower = text.lowercase()
        val found = LinkedHashSet<String>()
        var rest = lower
        for ((stem, key) in stems) {
            if (stem in rest) {
                found += key
                rest = rest.replace(stem, " ")
            }
        }
        return found
    }

    /** Rough main component for dishes typed as free text ("tacos fredag"). */
    fun mainComponent(text: String): MainComponent? {
        val keys = of(text)
        return when {
            keys.any { it in setOf("fisk", "lax", "fiskpinnar", "skaldjur") } -> MainComponent.Fish
            "kyckling" in keys || "kalkon" in keys -> MainComponent.Chicken
            keys.any {
                it in setOf("köttbullar", "köttfärs", "tacos", "lasagne", "hamburgare", "kåldolmar", "chili")
            } -> MainComponent.MincedMeat
            "korv" in keys -> MainComponent.Sausage
            keys.any { it in setOf("fläsk", "schnitzel") } -> MainComponent.Pork
            "biff" in keys -> MainComponent.Beef
            keys.any { it in setOf("soppa", "ärtsoppa") } -> MainComponent.Soup
            keys.any { it in setOf("pannkakor", "ägg") } -> MainComponent.Egg
            keys.any { it in setOf("falafel", "halloumi") } -> MainComponent.Vegetarian
            "pasta" in keys -> MainComponent.Pasta
            else -> null
        }
    }

    /**
     * The food group of a dish name ("Fiskgratäng med potatis", "Vegobullar"), or null when the name does not tell. Only the
     * first alternative counts ("Köttbullar / vegobullar" is köttbullar): schools list the vegetarian option second. A clearly
     * vegetarian word wins ("Vegetarisk lasagne"); beans and lentils only when no meat or fish is named ("Kyckling med bönor").
     * Lamb counts as red meat with beef (see the stems).
     */
    fun foodGroup(text: String): FoodGroup? {
        val first = text.lowercase().split(" / ", "/", " eller ", " alt ", " alt.", "\n").first()
        if (VEGETARIAN.any { it in first }) return FoodGroup.Vegetarian
        mainComponent(first)?.foodGroup()?.let { return it }
        return if (LEGUMES.any { it in first }) FoodGroup.Vegetarian else null
    }

    /** Meat, poultry or fish named in [text] (an ingredient list), ignoring vegetarian words: null when none is. */
    internal fun animalGroup(text: String): FoodGroup? = mainComponent(text.lowercase())?.foodGroup()?.takeIf { it != FoodGroup.Vegetarian }

    private val VEGETARIAN = listOf(
        "vego",
        "vegetar",
        "vegan",
        "quorn",
        "tofu",
        "halloumi",
        "falafel",
        "sojafärs",
        "oumph",
        "bönbiff",
        "linsbiff",
        "grönsaks",
    )
    private val LEGUMES = listOf("böna", "bönor", "bönsoppa", "lins", "kikärt")
}

object MealClash {
    /** The shared dish keys between a school lunch and a dinner; empty = no clash. */
    fun between(lunchDishes: List<String>, dinner: String): Set<String> {
        // Only the first (main) lunch option counts; schools often list a vegetarian alternative second.
        val lunch = DishKeywords.of(lunchDishes.firstOrNull().orEmpty())
        val evening = DishKeywords.of(dinner)
        return lunch intersect evening
    }
}

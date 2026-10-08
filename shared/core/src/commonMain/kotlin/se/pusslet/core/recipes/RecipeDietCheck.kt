package se.pusslet.core.recipes

import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Diet
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag

/** Why a recipe does not suit someone in the family. */
sealed interface DietConflict {
    val personId: String

    data class ContainsAllergen(override val personId: String, val allergen: Allergen, val ingredient: String) : DietConflict

    data class BreaksDiet(override val personId: String, val diet: Diet, val ingredient: String) : DietConflict
}

/**
 * Rule-based check of a recipe against the family's opt-in diet profiles. Matches word stems in ingredient names
 * ("vispgrädde" contains "grädde" -> milk). It is a helpful warning, not a guarantee: the UI says so, and the user
 * always sees which ingredient triggered it.
 */
object RecipeDietCheck {
    private val allergenStems: Map<Allergen, List<String>> = mapOf(
        Allergen.Milk to listOf(
            "mjölk", "grädde", "smör", "ost", "parmesan", "mozzarella", "fetaost", "halloumi", "crème fraiche", "creme fraiche",
            "gräddfil", "yoghurt", "kvarg", "keso", "färskost", "mascarpone", "ricotta", "kesella", "vassle",
        ),
        Allergen.Egg to listOf("ägg", "äggula", "äggvita", "majonnäs", "aioli"),
        Allergen.Gluten to listOf(
            "vetemjöl", "mjöl", "vete", "råg", "korn", "dinkel", "pasta", "spaghetti", "makaroner", "penne", "lasagneplattor",
            "nudlar", "bröd", "tortilla", "pizzadeg", "ströbröd", "skorpmjöl", "couscous", "bulgur", "pannkaks", "sojasås",
            "korngryn", "pärlkorn", "matvete", "lasagne",
        ),
        Allergen.Peanut to listOf("jordnöt"),
        Allergen.TreeNuts to listOf("mandel", "hasselnöt", "valnöt", "cashew", "pekannöt", "pistage", "paranöt", "pinjenöt", "nötter"),
        Allergen.Fish to listOf("fisk", "lax", "torsk", "sej", "kolja", "tonfisk", "sill", "makrill", "ansjovis", "fiskpinnar", "fisksås"),
        Allergen.Shellfish to listOf("räk", "krabba", "hummer", "kräft", "musslor", "blåmusslor", "kammussl", "bläckfisk"),
        Allergen.Soy to listOf("soja", "tofu", "edamame", "miso", "tempeh"),
        Allergen.Sesame to listOf("sesam", "tahini"),
        Allergen.Celery to listOf("selleri", "rotselleri", "blekselleri"),
        Allergen.Mustard to listOf("senap", "senapsfrö"),
        Allergen.Lupin to listOf("lupin"),
        Allergen.Sulphites to listOf("vin", "torkade aprikoser", "vinäger"),
    )

    /** Ingredient words that are not what the stem suggests ("ostronsås" is not cheese, "kokosmjölk" is not milk). */
    private val exceptions: Map<Allergen, List<String>> = mapOf(
        Allergen.Milk to listOf(
            "kokosmjölk", "havremjölk", "sojamjölk", "mandelmjölk", "havregrädde", "sojagrädde", "ostronsås", "kokosgrädde",
            "jordnötssmör", "mandelsmör", "vegansk", "växtbaserad",
        ),
        Allergen.Gluten to listOf(
            "glutenfri", "majsmjöl", "rismjöl", "potatismjöl", "mandelmjöl", "kokosmjöl", "risnudlar", "majstortilla", "bovete", "pastasås",
            "kikärtsmjöl", "majsbröd",
        ),
        Allergen.Egg to listOf("äggfri", "vegansk majonnäs"),
        Allergen.Sulphites to listOf("vindruv"),
    )

    private val meat = listOf(
        "kött", "färs", "nötfärs", "blandfärs", "fläskfärs", "kyckling", "kalkon", "bacon", "skinka", "korv", "fläsk", "biff",
        "entrecôte", "oxfilé", "lamm", "vilt", "älg", "rådjur", "salami", "chorizo", "pancetta", "kassler", "köttbullar", "kycklingbuljong",
        "köttbuljong", "hönsbuljong", "kalvfond", "oxfond", "viltfond",
    )
    private val pork = listOf("fläsk", "bacon", "skinka", "kassler", "pancetta", "chorizo", "salami", "falukorv", "prinskorv", "fläskfärs", "blandfärs")
    private val vegExceptions = listOf("grönsaksbuljong", "svampbuljong", "vegobuljong", "vegetarisk", "vegansk", "vego", "sojafärs", "quornfärs", "vegofärs")

    fun allergens(recipe: Recipe): Map<Allergen, String> {
        val found = LinkedHashMap<Allergen, String>()
        for (ing in recipe.ingredients) {
            val name = ing.name.lowercase()
            for ((allergen, stems) in allergenStems) {
                if (allergen in found) continue
                if (exceptions[allergen].orEmpty().any { it in name }) continue
                if (stems.any { matches(name, it) }) found[allergen] = ing.name
            }
            // "2 msk soja" in a Swedish recipe is soy sauce, which is brewed with wheat.
            if (name == "soja" || name == "kinesisk soja" || name == "japansk soja") found.getOrPut(Allergen.Gluten) { ing.name }
        }
        // Lactose follows milk; a lactose-free product is still milk protein.
        found[Allergen.Milk]?.let { ing -> if (!ing.lowercase().contains("laktosfri")) found[Allergen.Lactose] = ing }
        return found
    }

    /** The first ingredient that breaks [diet], or null if the recipe fits (tags count: a "vegetarian" recipe fits). */
    fun breaks(recipe: Recipe, diet: Diet): String? {
        val names = recipe.ingredients.map { it.name }
        fun first(stems: List<String>, except: List<String> = vegExceptions) = names.firstOrNull { n ->
            val l = n.lowercase()
            except.none { it in l } && stems.any { matches(l, it) }
        }
        val fish = allergenStems.getValue(Allergen.Fish) + allergenStems.getValue(Allergen.Shellfish)
        return when (diet) {
            Diet.Vegetarian -> if (RecipeTag.Vegetarian in recipe.tags || RecipeTag.Vegan in recipe.tags) null else first(meat + fish)
            Diet.Vegan -> if (RecipeTag.Vegan in recipe.tags) {
                null
            } else {
                first(meat + fish) ?: allergens(recipe).let { it[Allergen.Milk] ?: it[Allergen.Egg] } ?: first(listOf("honung"))
            }
            Diet.Pescetarian -> first(meat)
            Diet.NoPork, Diet.Halal, Diet.Kosher -> first(pork, emptyList())
        }
    }

    /**
     * Word-based so that short stems do not hit inside unrelated words ("ost" must not match "rostad lök"):
     * a word starts or ends with the stem, which covers Swedish compounds ("vispgrädde", "cheddarost", "räkor").
     */
    private fun matches(name: String, stem: String): Boolean {
        if (' ' in stem) return stem in name
        return name.split(wordSplit).any { w -> w.startsWith(stem) || w.endsWith(stem) }
    }

    private val wordSplit = Regex("""[^\p{L}]+""")

    fun conflicts(recipe: Recipe, profiles: List<DietProfile>): List<DietConflict> {
        if (profiles.isEmpty()) return emptyList()
        val allergens = allergens(recipe)
        return profiles.flatMap { p ->
            p.allergies.mapNotNull { a -> allergens[a]?.let { DietConflict.ContainsAllergen(p.personId, a, it) } } +
                p.diets.mapNotNull { d -> breaks(recipe, d)?.let { DietConflict.BreaksDiet(p.personId, d, it) } }
        }
    }
}

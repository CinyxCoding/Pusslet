package se.pusslet.core.nutrition

import kotlinx.serialization.Serializable
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.Recipe
import se.pusslet.core.units.Dimension
import se.pusslet.core.units.MeasureUnit

/** A food in Livsmedelsverket's database. */
data class Food(val number: Int, val name: String)

/** Per 100 g edible part. Null = not reported. */
@Serializable
data class NutrientValues(
    val energyKcal: Double? = null,
    val energyKj: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbohydrates: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
    val sugars: Double? = null,
) {
    operator fun plus(o: NutrientValues) = NutrientValues(
        add(energyKcal, o.energyKcal),
        add(energyKj, o.energyKj),
        add(protein, o.protein),
        add(fat, o.fat),
        add(carbohydrates, o.carbohydrates),
        add(fiber, o.fiber),
        add(salt, o.salt),
        add(sugars, o.sugars),
    )

    fun scaled(factor: Double) = NutrientValues(
        energyKcal?.times(factor),
        energyKj?.times(factor),
        protein?.times(factor),
        fat?.times(factor),
        carbohydrates?.times(factor),
        fiber?.times(factor),
        salt?.times(factor),
        sugars?.times(factor),
    )

    companion object {
        val ZERO = NutrientValues(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        private fun add(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    }
}

/**
 * Turns household amounts into grams. Densities and piece weights are rounded kitchen values; the summary
 * says how many ingredients could not be converted so the overview stays honest.
 */
object GramEstimator {
    /** g per ml for common ingredients (water-like = 1.0). Matched on a word in the ingredient name. */
    private val densities = linkedMapOf(
        "vetemjöl" to 0.6,
        "mjöl" to 0.6,
        "socker" to 0.85,
        "strösocker" to 0.85,
        "florsocker" to 0.55,
        "ris" to 0.85,
        "havregryn" to 0.4,
        "olja" to 0.92,
        "olivolja" to 0.92,
        "smör" to 0.95,
        "honung" to 1.4,
        "sirap" to 1.4,
        "kakao" to 0.45,
        "salt" to 1.2,
        "riven ost" to 0.4,
        "ost" to 0.45,
        "grädde" to 1.0,
        "mjölk" to 1.03,
        "crème fraiche" to 1.0,
        "creme fraiche" to 1.0,
        "yoghurt" to 1.03,
        "krossade tomater" to 1.05,
        "buljong" to 1.0,
        "vatten" to 1.0,
        "linser" to 0.85,
        "bönor" to 0.75,
        "pasta" to 0.45,
        "majs" to 0.75,
        "ärtor" to 0.65,
    )

    /** Typical edible weight in grams per piece. */
    private val pieces = linkedMapOf(
        "vitlöksklyfta" to 5.0,
        "vitlök" to 5.0,
        "ägg" to 55.0,
        "gul lök" to 100.0,
        "rödlök" to 90.0,
        "lök" to 100.0,
        "gurka" to 350.0,
        "tomat" to 100.0,
        "potatis" to 100.0,
        "morot" to 70.0,
        "morötter" to 70.0,
        "paprika" to 150.0,
        "banan" to 120.0,
        "äpple" to 150.0,
        "citron" to 100.0,
        "lime" to 60.0,
        "avokado" to 150.0,
        "zucchini" to 250.0,
        "kycklingfilé" to 125.0,
        "kycklingbröst" to 125.0,
        "tortilla" to 40.0,
        "tortillabröd" to 40.0,
        "purjolök" to 200.0,
        "broccoli" to 400.0,
        "blomkål" to 600.0,
        "sötpotatis" to 300.0,
        "champinjon" to 15.0,
        "kruka basilika" to 25.0,
    )

    fun grams(ingredient: Ingredient): Double? {
        val q = ingredient.quantity ?: return null
        val name = ingredient.name.lowercase()
        val unit = ingredient.unit
        return when {
            unit == null || unit == MeasureUnit.St -> pieceWeight(name)?.times(q)
            unit.dimension == Dimension.Mass -> q * unit.baseFactor
            unit.dimension == Dimension.Volume -> q * unit.baseFactor * (density(name) ?: 1.0)
            unit == MeasureUnit.Klyfta -> q * 5.0
            unit == MeasureUnit.Burk -> q * 400.0
            unit == MeasureUnit.Forp || unit == MeasureUnit.Pkt -> null
            else -> null
        }
    }

    private fun density(name: String) = densities.entries.firstOrNull { name.contains(it.key) }?.value

    private fun pieceWeight(name: String) = pieces.entries.firstOrNull { name.contains(it.key) }?.value
}

data class NutritionSummary(
    val total: NutrientValues,
    val perPortion: NutrientValues,
    val matchedIngredients: Int,
    val totalIngredients: Int,
) {
    val complete: Boolean get() = matchedIngredients == totalIngredients
}

object NutritionCalculator {
    /**
     * @param foodFor resolves an ingredient to a food number (confirmed mapping or best match), or null.
     * @param valuesFor nutrient values per 100 g for a food number.
     */
    fun forRecipe(
        recipe: Recipe,
        foodFor: (Ingredient) -> Int?,
        valuesFor: (Int) -> NutrientValues?,
        servings: Int = recipe.servings,
    ): NutritionSummary {
        var total = NutrientValues.ZERO
        var matched = 0
        val relevant = recipe.ingredients.filter { !it.optional }
        for (ing in relevant) {
            val grams = GramEstimator.grams(ing) ?: continue
            val number = ing.foodNumber ?: foodFor(ing) ?: continue
            val values = valuesFor(number) ?: continue
            total += values.scaled(grams / 100.0)
            matched++
        }
        val scale = servings.toDouble() / recipe.servings.coerceAtLeast(1)
        val scaledTotal = total.scaled(scale)
        return NutritionSummary(scaledTotal, scaledTotal.scaled(1.0 / servings.coerceAtLeast(1)), matched, relevant.size)
    }
}

/** The week's planned recipes, averaged per portion. [estimated] of [meals] could be calculated at all. */
data class WeekNutrition(
    val meals: Int,
    val estimated: Int,
    val averagePerPortion: NutrientValues,
    /** Every meal estimated, and every ingredient in them matched. */
    val complete: Boolean,
)

object WeekNutritionCalculator {
    /** [summaries] has one entry per planned recipe meal; null = could not be estimated. */
    fun average(summaries: List<NutritionSummary?>): WeekNutrition {
        val known = summaries.filterNotNull()
        // Per value: a nutrient a recipe does not report is unknown, not zero.
        fun avg(value: (NutrientValues) -> Double?): Double? =
            known.mapNotNull { value(it.perPortion) }.takeIf { it.isNotEmpty() }?.average()
        val average = NutrientValues(
            energyKcal = avg { it.energyKcal },
            energyKj = avg { it.energyKj },
            protein = avg { it.protein },
            fat = avg { it.fat },
            carbohydrates = avg { it.carbohydrates },
            fiber = avg { it.fiber },
            salt = avg { it.salt },
            sugars = avg { it.sugars },
        )
        return WeekNutrition(
            meals = summaries.size,
            estimated = known.size,
            averagePerPortion = average,
            complete = known.size == summaries.size && known.all { it.complete },
        )
    }
}

/**
 * Suggests database foods for an ingredient name. Simple and explainable: word overlap with a bonus for the first word,
 * a preference for raw/plain items ("rå", short names) and a penalty for prepared dishes. The user confirms the match.
 */
object FoodMatcher {
    private val preparedHints = listOf("hemlagad", "färdigrätt", "gratäng", "soppa", "pudding", "sufflé", "sås", "paj", "kokt m.", "stekt")
    private val rawHints = listOf("rå", "okokt", "torkad", "fett 3%")

    fun normalize(s: String): List<String> = s.lowercase().replace(Regex("""[^\p{L}\d%]+"""), " ").split(' ').filter { it.length > 1 }

    fun score(ingredientName: String, food: Food): Double {
        val want = normalize(ingredientName)
        if (want.isEmpty()) return 0.0
        val have = normalize(food.name)
        val first = have.firstOrNull() ?: return 0.0
        var s = 0.0
        for (w in want) {
            when {
                have.any { it == w } -> s += 2.0
                have.any { it.startsWith(w) || w.startsWith(it) } -> s += 1.2
                have.any { it.contains(w) || w.contains(it) } -> s += 0.6
            }
        }
        if (first == want.first() || first.startsWith(want.first())) s += 1.5
        val lower = food.name.lowercase()
        if (preparedHints.any { it in lower }) s -= 1.5
        if (rawHints.any { it in lower }) s += 0.4
        s -= have.size * 0.08
        return s
    }

    fun best(ingredientName: String, candidates: List<Food>, limit: Int = 5): List<Food> =
        candidates.map {
            it to score(ingredientName, it)
        }.filter { it.second > 1.0 }.sortedByDescending { it.second }.take(limit).map { it.first }
}

package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.Recipe
import se.pusslet.core.time.IsoWeek

/**
 * The food groups Livsmedelsverket's advice speaks of, as far as a dish name tells. Amounts are never known: how much a
 * child eats is individual, so the balance counts meals, not grams.
 */
enum class FoodGroup { Fish, Poultry, RedMeat, Vegetarian }

/** Beef, pork, minced meat and sausage are red meat; egg dishes (pannkakor, omelett) are vegetarian; pasta and soup do not tell. */
fun MainComponent.foodGroup(): FoodGroup? = when (this) {
    MainComponent.Fish -> FoodGroup.Fish
    MainComponent.Chicken -> FoodGroup.Poultry
    MainComponent.Beef, MainComponent.Pork, MainComponent.MincedMeat, MainComponent.Sausage -> FoodGroup.RedMeat
    MainComponent.Vegetarian, MainComponent.Egg -> FoodGroup.Vegetarian
    MainComponent.Pasta, MainComponent.Soup, MainComponent.Other -> null
}

/** How many meals of each group, and how many whose name told nothing ([unknown]). */
data class GroupCounts(val counts: Map<FoodGroup, Int> = emptyMap(), val unknown: Int = 0) {
    operator fun get(group: FoodGroup): Int = counts[group] ?: 0

    val known: Int get() = counts.values.sum()

    operator fun plus(other: GroupCounts) = GroupCounts(
        FoodGroup.entries.associateWith { this[it] + other[it] }.filterValues { it > 0 },
        unknown + other.unknown,
    )

    companion object {
        fun of(groups: Collection<FoodGroup?>) = GroupCounts(
            groups.filterNotNull().groupingBy { it }.eachCount(),
            groups.count { it == null },
        )
    }
}

/**
 * One eater's week: [childIds] with the same school lunches (lunch and dinner counted together), or the family's dinners
 * alone when no child has a school lunch that week ([childIds] empty).
 */
data class EaterWeek(val childIds: List<String>, val lunches: GroupCounts, val dinners: GroupCounts) {
    val total: GroupCounts get() = lunches + dinners
}

/** What the rest of the week could use, for the suggestions: groups below the advice, and whether red meat is at its ceiling. */
data class WeekNeeds(val wanted: Set<FoodGroup> = emptySet(), val lessRedMeat: Boolean = false) {
    val isEmpty: Boolean get() = wanted.isEmpty() && !lessRedMeat
}

data class WeekBalance(val week: IsoWeek, val eaters: List<EaterWeek>, val needs: WeekNeeds) {
    /** Nothing at all is known about the week's food yet. */
    val isEmpty: Boolean get() = eaters.all { it.total.known == 0 }
}

/**
 * Livsmedelsverket's advice applied to what is planned (rule based, ADR-001): fish 2–3 times a week, more vegetarian food, at
 * most about 350–500 g of red meat and charcuterie a week – read here as [FISH_MIN] fish meals, [VEGETARIAN_MIN] vegetarian
 * meals and at most [RED_MEAT_MAX] red-meat meals, school lunch and dinner together. A group is wanted when any child (or,
 * without school lunches, the family) is below it; the rest of the week then leans that way.
 */
object WeekBalancer {
    const val FISH_MIN = 2
    const val VEGETARIAN_MIN = 2
    const val RED_MEAT_MAX = 3

    /** The group of a school lunch: its first dish (the second is usually the vegetarian alternative). */
    fun lunchGroup(dishes: List<String>): FoodGroup? = dishes.firstOrNull()?.let(DishKeywords::foodGroup)

    /**
     * The group of a recipe: its main component, else its title, else its ingredients – meat or fish among them, and a recipe
     * with ingredients but neither is vegetarian ("Pasta pesto").
     */
    fun recipeGroup(recipe: Recipe): FoodGroup? {
        recipe.mainComponent?.foodGroup()?.let { return it }
        DishKeywords.foodGroup(recipe.title)?.let { return it }
        if (recipe.ingredients.isEmpty()) return null
        return DishKeywords.animalGroup(recipe.ingredients.joinToString(" ") { it.name }) ?: FoodGroup.Vegetarian
    }

    /** A planned dinner's group; null both for "no cooking" (nothing eaten from the plan) and an unknown dish – see [eaten]. */
    fun dinnerGroup(entry: MealEntry, recipes: Map<String, Recipe>): FoodGroup? =
        entry.recipeId?.let(recipes::get)?.let(::recipeGroup) ?: DishKeywords.foodGroup(entry.title)

    /** "Ingen matlagning" says nothing about what is eaten, so it is left out; takeaway counts by its name. */
    fun eaten(entry: MealEntry): Boolean = entry.kind != MealKind.NoCooking

    /**
     * [dinners]: the week's planned dinners that are eaten (date -> group, null = unknown). [lunches]: per child, the school
     * lunches of the week (date -> group). Children with the same lunches share one [EaterWeek].
     */
    fun balance(week: IsoWeek, dinners: Map<LocalDate, FoodGroup?>, lunches: Map<String, Map<LocalDate, FoodGroup?>>): WeekBalance {
        val inWeek = week.days.toSet()
        val dinnerCounts = GroupCounts.of(dinners.filterKeys { it in inWeek }.values)
        val withLunch = lunches.mapValues { (_, days) -> days.filterKeys { it in inWeek } }.filterValues { it.isNotEmpty() }
        val eaters = if (withLunch.isEmpty()) {
            listOf(EaterWeek(emptyList(), GroupCounts(), dinnerCounts))
        } else {
            withLunch.entries.groupBy({ it.value }, { it.key }).map { (days, ids) ->
                EaterWeek(ids, GroupCounts.of(days.values), dinnerCounts)
            }
        }
        return WeekBalance(week, eaters, needs(eaters))
    }

    private fun needs(eaters: List<EaterWeek>): WeekNeeds {
        val totals = eaters.map { it.total }
        val wanted = buildSet {
            if (totals.any { it[FoodGroup.Fish] < FISH_MIN }) add(FoodGroup.Fish)
            if (totals.any { it[FoodGroup.Vegetarian] < VEGETARIAN_MIN }) add(FoodGroup.Vegetarian)
        }
        return WeekNeeds(wanted, lessRedMeat = totals.any { it[FoodGroup.RedMeat] >= RED_MEAT_MAX })
    }
}

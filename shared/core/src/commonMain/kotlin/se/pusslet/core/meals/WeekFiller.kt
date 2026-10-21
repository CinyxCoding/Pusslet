package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.time.isoWeek

/** A proposed dinner for [date]: [options] best first, so "Byt" can step to the next one. */
data class DayProposal(val date: LocalDate, val options: List<MealSuggestion>)

/**
 * "Föreslå veckans middagar": a dinner for each day still to plan, day by day, each choice counted in the week's balance
 * before the next day is picked ([WeekBalancer]), so the week ends up with fish and vegetarian days around the school lunches.
 * The same rules as one day's suggestions ([MealSuggester]); no recipe is proposed twice in a week.
 */
object WeekFiller {
    /** Alternatives kept per day for "Byt". */
    const val OPTIONS = 3

    /**
     * [days]: the dates to fill, in order. [planned]: the week's other dinners that are eaten (date -> group). [lunchGroups]: per
     * child, the week's school lunches (date -> group); [lunchDishes]: per date, the school lunches' first dishes. [busy]: dates
     * with an evening activity.
     */
    fun fill(
        days: List<LocalDate>,
        recipes: List<Recipe>,
        history: List<MealEntry>,
        planned: Map<LocalDate, FoodGroup?>,
        lunchGroups: Map<String, Map<LocalDate, FoodGroup?>>,
        lunchDishes: Map<LocalDate, List<String>> = emptyMap(),
        busy: Set<LocalDate> = emptySet(),
        pantryNames: Set<String> = emptySet(),
    ): List<DayProposal> {
        val dinners = planned.toMutableMap()
        val chosen = mutableListOf<MealEntry>()
        return days.mapNotNull { date ->
            val needs = WeekBalancer.balance(date.isoWeek, dinners, lunchGroups).needs
            val usedIds = chosen.mapNotNull { it.recipeId }.toSet()
            val options = MealSuggester.suggest(
                date = date,
                recipes = recipes.filter { it.id !in usedIds },
                history = history + chosen,
                schoolLunchDishes = lunchDishes[date].orEmpty(),
                busyEvening = date in busy,
                pantryNames = pantryNames,
                week = needs,
                limit = OPTIONS,
            )
            val pick = options.firstOrNull() ?: return@mapNotNull null
            dinners[date] = WeekBalancer.recipeGroup(pick.recipe)
            chosen += MealEntry(date = date, slot = MealSlot.Dinner, recipeId = pick.recipe.id, title = pick.recipe.title)
            DayProposal(date, options)
        }
    }
}

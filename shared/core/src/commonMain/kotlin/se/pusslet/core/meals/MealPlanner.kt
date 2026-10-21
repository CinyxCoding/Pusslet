package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.units.Dimension
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import kotlin.math.ceil

object RecipeScaler {
    /** Ingredients for [servings] portions, in readable units ("15 tsk" -> "5 msk"); whole pieces stay whole-ish. */
    fun scale(recipe: Recipe, servings: Int): List<Ingredient> {
        val factor = servings.toDouble() / recipe.servings.coerceAtLeast(1)
        if (factor == 1.0) return recipe.ingredients
        return recipe.ingredients.map { ing ->
            val q = ing.amount ?: return@map ing
            val scaled = q.scaled(factor)
            val nice = if (q.unit?.dimension == Dimension.Count) roundPieces(scaled) else Units.normalize(scaled)
            ing.copy(quantity = nice.value, unit = nice.unit, quantityMax = nice.max)
        }
    }

    /** 1,3 lökar is not a thing: round counts to halves, and up to whole pieces above 2. */
    private fun roundPieces(q: Quantity): Quantity {
        fun r(v: Double) = if (v > 2) ceil(v - 0.05) else ceil(v * 2 - 0.1) / 2
        return q.copy(value = r(q.value), max = q.max?.let(::r))
    }
}

object MealPlanner {
    /** "Gör dubbelt": tomorrow's lunch box is the leftovers (no extra shopping, the dinner itself is doubled). */
    fun leftoversFor(entry: MealEntry): MealEntry = MealEntry(
        date = entry.date.plusDays(1),
        slot = MealSlot.Lunch,
        recipeId = null,
        title = entry.title,
        servings = entry.servings,
        leftoversFrom = entry.date,
    )

    /** A copy of [source] moved to [target] (same weekdays). Leftover links move along. */
    fun copyWeek(source: MealPlanWeek, target: IsoWeek): MealPlanWeek {
        val days = (target.monday.toEpochDays() - source.week.monday.toEpochDays()).toInt()
        val moved = source.entries.values.map { e -> e.copy(date = e.date.plusDays(days), leftoversFrom = e.leftoversFrom?.plusDays(days)) }
        return MealPlanWeek(target, moved.associateBy { it.key })
    }

    fun favoriteFrom(week: MealPlanWeek, id: String, name: String): FavoriteWeek = FavoriteWeek(
        id = id,
        name = name,
        days = week.entries.values
            .filter { it.slot == MealSlot.Dinner && it.leftoversFrom == null }
            .associate { (it.date.dayOfWeek.isoDayNumber - 1) to it.copy(date = week.week.monday) },
    )

    fun applyFavorite(favorite: FavoriteWeek, target: IsoWeek, existing: MealPlanWeek): MealPlanWeek {
        var week = existing.copy(week = target)
        favorite.days.forEach { (offset, template) ->
            val entry = template.copy(date = target.monday.plusDays(offset), slot = MealSlot.Dinner)
            week = week.with(entry)
            if (entry.cookDouble) week = week.with(leftoversFor(entry))
        }
        return week
    }
}

/** Why a dinner is suggested; shown as small chips so the suggestion is explainable (no black box). */
enum class SuggestionReason {
    Favorite,
    NotRecently,
    QuickForBusyEvening,
    UsesWhatYouHave,
    VariesTheWeek,
    DiffersFromSchoolLunch,

    /** The week (school lunch and dinner) has less fish than Livsmedelsverket's advice ([WeekBalancer]). */
    FishThisWeek,

    /** The week has few vegetarian meals. */
    VegetarianThisWeek,

    /** Red meat is already at the advice's ceiling this week, and this is not red meat. */
    LessRedMeat,
}

data class MealSuggestion(val recipe: Recipe, val reasons: Set<SuggestionReason>, val score: Int)

/**
 * Rule-based dinner suggestions: favourites you have not cooked for a while, quick dishes on busy evenings,
 * something other than what the children had for lunch, and recipes that use what is already at home.
 */
object MealSuggester {
    private const val RECENT_DAYS = 14
    private const val LONG_AGO_DAYS = 28

    /** What is known about the day being planned. */
    private class Day(
        val date: LocalDate,
        val lastCooked: Map<String?, LocalDate>,
        val yesterday: MainComponent?,
        val lunchDishes: List<String>,
        val lunchComponents: Set<MainComponent>,
        val busy: Boolean,
        val pantry: Set<String>,
        val week: WeekNeeds,
    )

    private class Score {
        var points = 0
        val reasons = mutableSetOf<SuggestionReason>()

        fun add(p: Int, reason: SuggestionReason? = null) {
            points += p
            reason?.let(reasons::add)
        }
    }

    /**
     * A busy evening asks for something quick only when the family cooks that evening ([kind] null = not planned yet):
     * with takeaway or no cooking there is nothing to hurry.
     */
    fun needsQuickDinner(busy: Boolean, kind: MealKind?): Boolean = busy && (kind == null || kind.cooks)

    fun suggest(
        date: LocalDate,
        recipes: List<Recipe>,
        history: List<MealEntry>,
        schoolLunchDishes: List<String> = emptyList(),
        busyEvening: Boolean = false,
        pantryNames: Set<String> = emptySet(),
        week: WeekNeeds = WeekNeeds(),
        limit: Int = 5,
    ): List<MealSuggestion> {
        // Takeaway and no cooking are not dishes cooked here: they neither count as made recently nor vary the week.
        val cooked = history.filter { it.kind.cooks }
        val yesterday = cooked.firstOrNull { it.date == date.plusDays(-1) && it.slot == MealSlot.Dinner }
        val day = Day(
            date = date,
            lastCooked = cooked.filter { it.recipeId != null && it.date < date }
                .groupBy { it.recipeId }
                .mapValues { (_, v) -> v.maxOf { it.date } },
            yesterday = yesterday?.recipeId?.let { id -> recipes.firstOrNull { it.id == id }?.mainComponent }
                ?: yesterday?.let { DishKeywords.mainComponent(it.title) },
            lunchDishes = schoolLunchDishes,
            lunchComponents = schoolLunchDishes.mapNotNull { DishKeywords.mainComponent(it) }.toSet(),
            busy = busyEvening,
            pantry = pantryNames.map(ShoppingNames::canonical).toSet(),
            week = week,
        )
        return recipes.map { r -> score(r, day) }
            .sortedWith(compareByDescending<MealSuggestion> { it.score }.thenBy { it.recipe.title })
            .take(limit)
    }

    private fun score(r: Recipe, day: Day): MealSuggestion {
        val s = Score()
        freshness(r, day, s)
        if (r.favorite) s.add(20, SuggestionReason.Favorite)
        if (day.busy) {
            if (r.isQuick) {
                s.add(25, SuggestionReason.QuickForBusyEvening)
            } else {
                s.add(-15)
            }
        }
        variety(r, day, s)
        balance(r, day.week, s)
        val uses = r.ingredients.count { ShoppingNames.canonical(it.name) in day.pantry }
        if (uses > 0) s.add(8 * uses, SuggestionReason.UsesWhatYouHave)
        if (RecipeTag.KidFriendly in r.tags) s.add(3)
        return MealSuggestion(r, s.reasons, s.points)
    }

    private fun freshness(r: Recipe, day: Day, s: Score) {
        val days = day.lastCooked[r.id]?.let { (day.date.toEpochDays() - it.toEpochDays()).toInt() }
        if (days != null && days < RECENT_DAYS) s.add(-50)
        if (days == null || days >= LONG_AGO_DAYS) s.add(10, SuggestionReason.NotRecently)
    }

    /** Leans the rest of the week towards Livsmedelsverket's advice ([WeekBalancer]): more fish and vegetarian, less red meat. */
    private fun balance(r: Recipe, week: WeekNeeds, s: Score) {
        if (week.isEmpty) return
        val group = WeekBalancer.recipeGroup(r) ?: return
        when {
            group in week.wanted && group == FoodGroup.Fish -> s.add(25, SuggestionReason.FishThisWeek)
            group in week.wanted && group == FoodGroup.Vegetarian -> s.add(25, SuggestionReason.VegetarianThisWeek)
            week.lessRedMeat && group == FoodGroup.RedMeat -> s.add(-20)
            week.lessRedMeat -> s.add(5, SuggestionReason.LessRedMeat)
        }
    }

    /** Not the same as the school lunch, not the same as yesterday. */
    private fun variety(r: Recipe, day: Day, s: Score) {
        val component = r.mainComponent ?: DishKeywords.mainComponent(r.title)
        when {
            component != null && component in day.lunchComponents -> s.add(-30)
            day.lunchComponents.isNotEmpty() -> s.add(0, SuggestionReason.DiffersFromSchoolLunch)
        }
        if (MealClash.between(day.lunchDishes, r.title).isNotEmpty()) s.add(-40)
        when {
            component == null || day.yesterday == null -> Unit
            component != day.yesterday -> s.add(5, SuggestionReason.VariesTheWeek)
            else -> s.add(-20)
        }
    }
}

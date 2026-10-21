package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity

@Serializable
data class Ingredient(
    val name: String,
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
    val quantityMax: Double? = null,
    /** "finhackad", "eller 2 dl matlagningsgrädde" … */
    val note: String = "",
    /** Section heading within the recipe ("Sås", "Till servering"). */
    val group: String? = null,
    /** Confirmed match in Livsmedelsverket's database (livsmedelsnummer). */
    val foodNumber: Int? = null,
    /** Pantry staples like salt and water are usually not added to the shopping list. */
    val optional: Boolean = false,
) {
    val amount: Quantity? get() = quantity?.let { Quantity(it, unit, quantityMax) }
}

@Serializable
enum class RecipeTag {
    @SerialName("quick")
    Quick,

    @SerialName("vegetarian")
    Vegetarian,

    @SerialName("vegan")
    Vegan,

    @SerialName("kid_friendly")
    KidFriendly,

    @SerialName("lunchbox")
    Lunchbox,

    @SerialName("freezer")
    FreezerFriendly,

    @SerialName("weekend")
    Weekend,

    @SerialName("budget")
    Budget,
}

/** The main protein/component, used to vary the week and avoid repeating the school lunch. */
@Serializable
enum class MainComponent {
    @SerialName("fish")
    Fish,

    @SerialName("chicken")
    Chicken,

    @SerialName("beef")
    Beef,

    @SerialName("pork")
    Pork,

    @SerialName("minced_meat")
    MincedMeat,

    @SerialName("sausage")
    Sausage,

    @SerialName("vegetarian")
    Vegetarian,

    @SerialName("egg")
    Egg,

    @SerialName("pasta")
    Pasta,

    @SerialName("soup")
    Soup,

    @SerialName("other")
    Other,
}

@Serializable
data class Recipe(
    val id: String,
    val title: String,
    val servings: Int = 4,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tags: Set<RecipeTag> = emptySet(),
    val prepMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val mainComponent: MainComponent? = null,
    /** Imported recipes keep their source so it can be shown and credited. */
    val sourceUrl: String? = null,
    val sourceName: String? = null,
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val notes: String = "",
) {
    val isQuick: Boolean get() = RecipeTag.Quick in tags || (totalMinutes ?: Int.MAX_VALUE) <= 30
}

/**
 * A family-named collection of recipes ("Vardagsfavoriter", "Kalas", "Matlådor"). Document id: [id]. The recipes are a map
 * (recipe id -> true) and not a list, so two parents adding different recipes at the same time both keep theirs (the sync
 * merges the map per key); renaming touches one field and deleting the collection never touches the recipes.
 */
@Serializable
data class RecipeCollection(
    val id: String,
    val name: String,
    val recipeIds: Map<String, Boolean> = emptyMap(),
) {
    operator fun contains(recipeId: String): Boolean = recipeIds[recipeId] == true

    fun with(recipeId: String): RecipeCollection = copy(recipeIds = recipeIds + (recipeId to true))

    fun without(recipeId: String): RecipeCollection = copy(recipeIds = recipeIds - recipeId)
}

@Serializable
enum class MealSlot {
    @SerialName("breakfast")
    Breakfast,

    @SerialName("lunch")
    Lunch,

    @SerialName("dinner")
    Dinner,
}

/** How a meal comes about: cooked at home (the default), "Hämtmat" or "Ingen matlagning" (out, at mormor's, smörgås). */
@Serializable
enum class MealKind {
    @SerialName("cooked")
    Cooked,

    /** "Hämtmat": bought ready, so no recipe, no shopping and nobody cooks. */
    @SerialName("takeaway")
    Takeaway,

    /** "Ingen matlagning": eating out, at someone else's or a sandwich evening. */
    @SerialName("none")
    NoCooking,
    ;

    /** Something is cooked at home: recipes, suggestions, "Gör dubbelt", "Vem lagar" and the shopping list apply. */
    val cooks: Boolean get() = this == Cooked
}

@Serializable
data class MealEntry(
    val date: LocalDate,
    val slot: MealSlot = MealSlot.Dinner,
    val recipeId: String? = null,
    /** Free text ("tacos"), the recipe title or, for takeaway and no cooking without a text, the kind's label. */
    val title: String,
    val servings: Int? = null,
    /** "Gör dubbelt": creates a leftovers day automatically. */
    val cookDouble: Boolean = false,
    /** Set on the leftovers/lunchbox entry that a double batch produced. */
    val leftoversFrom: LocalDate? = null,
    val note: String = "",
    /** Older documents have no kind: they were all cooked. */
    val kind: MealKind = MealKind.Cooked,
    /** "Vem lagar": the adult member who cooks (cooked meals only). */
    val cookId: String? = null,
) {
    val key: String get() = key(date, slot)

    /** What is cooked at home and needs ingredients: never takeaway or no cooking, never the leftovers of a double batch. */
    val cooksRecipe: Boolean get() = kind.cooks && recipeId != null && leftoversFrom == null

    /** Takeaway and no cooking keep only what applies to them: no recipe, portions, double batch or cook. */
    fun normalized(): MealEntry = if (kind.cooks) this else copy(recipeId = null, servings = null, cookDouble = false, cookId = null)

    companion object {
        fun key(date: LocalDate, slot: MealSlot) = "${date}_${slot.name}"
    }
}

/** One ISO week of meals. Document id: [IsoWeek.key]. Entries are merged per key so two parents can plan different days at once. */
@Serializable
data class MealPlanWeek(
    val week: IsoWeek,
    val entries: Map<String, MealEntry> = emptyMap(),
    val favoriteName: String? = null,
) {
    fun entry(date: LocalDate, slot: MealSlot = MealSlot.Dinner): MealEntry? = entries[MealEntry.key(date, slot)]

    fun with(entry: MealEntry): MealPlanWeek = copy(entries = entries + (entry.key to entry))

    fun without(date: LocalDate, slot: MealSlot): MealPlanWeek = copy(entries = entries - MealEntry.key(date, slot))
}

/** A saved week template ("favoritvecka"), applied relative to Monday. */
@Serializable
data class FavoriteWeek(
    val id: String,
    val name: String,
    /** Day offset from Monday (0..6) to entry template. */
    val days: Map<Int, MealEntry> = emptyMap(),
)

/** Today's school lunch for a child. */
data class SchoolLunch(val date: LocalDate, val dishes: List<String>, val source: String)

/** Manually entered school lunches (fallback when no feed exists). Document id: "{childId}_{week}". Merged per day. */
@Serializable
data class ManualLunchWeek(
    val childId: String,
    val week: IsoWeek,
    /** ISO date -> dishes separated by newlines. */
    val days: Map<String, String> = emptyMap(),
) {
    val docId: String get() = docId(childId, week)

    fun lunches(source: String): List<SchoolLunch> = days.mapNotNull { (date, text) ->
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
        val dishes = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (dishes.isEmpty()) null else SchoolLunch(d, dishes, source)
    }

    companion object {
        fun docId(childId: String, week: IsoWeek) = "${childId}_${week.key}"
    }
}

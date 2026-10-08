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

@Serializable
enum class MealSlot {
    @SerialName("breakfast")
    Breakfast,

    @SerialName("lunch")
    Lunch,

    @SerialName("dinner")
    Dinner,
}

@Serializable
data class MealEntry(
    val date: LocalDate,
    val slot: MealSlot = MealSlot.Dinner,
    val recipeId: String? = null,
    /** Free text ("tacos") or the recipe title. */
    val title: String,
    val servings: Int? = null,
    /** "Gör dubbelt": creates a leftovers day automatically. */
    val cookDouble: Boolean = false,
    /** Set on the leftovers/lunchbox entry that a double batch produced. */
    val leftoversFrom: LocalDate? = null,
    val note: String = "",
) {
    val key: String get() = key(date, slot)

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

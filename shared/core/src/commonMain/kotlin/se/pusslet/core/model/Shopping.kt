package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity
import kotlin.time.Instant

/** Store departments in a typical Swedish grocery store, in the default walking order. */
@Serializable
enum class Aisle {
    @SerialName("produce")
    Produce,

    @SerialName("bakery")
    Bakery,

    @SerialName("deli")
    Deli,

    @SerialName("meat")
    Meat,

    @SerialName("fish")
    Fish,

    @SerialName("dairy")
    Dairy,

    @SerialName("cheese")
    Cheese,

    @SerialName("eggs")
    Eggs,

    @SerialName("frozen")
    Frozen,

    @SerialName("pantry")
    Pantry,

    @SerialName("canned")
    Canned,

    @SerialName("spices")
    SpicesAndSauces,

    @SerialName("baking")
    Baking,

    @SerialName("breakfast")
    Breakfast,

    @SerialName("drinks")
    Drinks,

    @SerialName("snacks")
    SnacksAndSweets,

    @SerialName("baby")
    Baby,

    @SerialName("hygiene")
    Hygiene,

    @SerialName("household")
    Household,

    @SerialName("pets")
    Pets,

    @SerialName("pharmacy")
    Pharmacy,

    @SerialName("other")
    Other,
    ;

    companion object {
        /** Walking order of a typical Swedish supermarket: fruit & veg first, frozen and household near the tills. */
        val defaultOrder: List<Aisle> = listOf(
            Produce,
            Bakery,
            Deli,
            Meat,
            Fish,
            Cheese,
            Dairy,
            Eggs,
            Pantry,
            Canned,
            SpicesAndSauces,
            Baking,
            Breakfast,
            Drinks,
            SnacksAndSweets,
            Frozen,
            Baby,
            Hygiene,
            Household,
            Pets,
            Pharmacy,
            Other,
        )
    }
}

@Serializable
data class Store(
    val id: String,
    val name: String,
    val aisleOrder: List<Aisle> = Aisle.defaultOrder,
)

@Serializable
enum class ListKind {
    @SerialName("grocery")
    Grocery,

    @SerialName("other")
    Other,
}

@Serializable
data class ShoppingList(
    val id: String,
    val name: String,
    val kind: ListKind = ListKind.Grocery,
    val storeId: String? = null,
    val sortIndex: Int = 0,
)

/**
 * Where a line on the shopping list came from, so regenerating from the meal plan does not duplicate it.
 * [quantity]/[unit] remember this source's share, so manual additions survive a regeneration.
 */
@Serializable
data class ItemSource(
    val recipeId: String? = null,
    val mealDate: LocalDate? = null,
    val label: String = "",
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
)

/** Each item is its own document (fewer conflicts when the whole family adds at once). */
@Serializable
data class ShoppingItem(
    val id: String,
    val listId: String,
    val name: String,
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
    val note: String = "",
    val aisle: Aisle = Aisle.Other,
    val checked: Boolean = false,
    val checkedAt: Instant? = null,
    val checkedBy: String? = null,
    val addedBy: String? = null,
    val addedAt: Instant? = null,
    val sources: List<ItemSource> = emptyList(),
    val barcode: String? = null,
    /** Optional own price in SEK ("spara egna priser"). */
    val price: Double? = null,
) {
    val amount: Quantity? get() = quantity?.let { Quantity(it, unit) }
}

/** Where something is kept at home ("Skafferi", "Kyl", "Frys"). */
@Serializable
enum class PantryLocation {
    @SerialName("pantry")
    Pantry,

    @SerialName("fridge")
    Fridge,

    @SerialName("freezer")
    Freezer,
}

/**
 * "Det vi har hemma": things the family already has, used when the list is made from the meal plan and for "Använder det
 * ni har hemma" in the meal suggestions. Every field after [name] has a default, so documents written before they existed
 * still decode.
 *
 * The three states:
 * - **Slut** ([inStock] false): kept in the list so it is quick to mark again, but counts as not at home at all.
 * - **Finns hemma** ([inStock] and no [quantity]): there is some, nobody knows how much. The list from the meal plan
 *   still gets the ingredient, marked "Finns hemma?", so the family decides; it is never skipped silently.
 * - **Known amount** ([inStock] and a [quantity]): deducted from what the recipes need, but only in a unit that converts
 *   (2 dl at home counts against 3 dl, never against 400 g). An amount without [unit] means pieces ("st").
 *
 * Nothing here is changed by shopping or cooking: the family keeps it up to date by hand.
 */
@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
    val location: PantryLocation = PantryLocation.Pantry,
    val bestBefore: LocalDate? = null,
    val inStock: Boolean = true,
    /** Set when it was added by scanning, so scanning the same package again finds it. */
    val barcode: String? = null,
) {
    /** At home with an amount we can count with. */
    val amount: Quantity? get() = if (inStock) quantity?.let { Quantity(it, unit ?: MeasureUnit.St) } else null

    /** At home, but nobody noted how much ("Finns hemma"). */
    val amountUnknown: Boolean get() = inStock && quantity == null
}

/** "Sara är i affären": visible to the family while someone is shopping. Document id: uid. */
@Serializable
data class ShoppingPresence(
    val memberId: String,
    val listId: String,
    val startedAt: Instant,
    val until: Instant,
)

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
            Produce, Bakery, Deli, Meat, Fish, Cheese, Dairy, Eggs, Pantry, Canned, SpicesAndSauces, Baking,
            Breakfast, Drinks, SnacksAndSweets, Frozen, Baby, Hygiene, Household, Pets, Pharmacy, Other,
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

/** Where a line on the shopping list came from, so regenerating from the meal plan does not duplicate it. */
@Serializable
data class ItemSource(val recipeId: String? = null, val mealDate: LocalDate? = null, val label: String = "")

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

/** "Hemma"-lagret: things the family already has. Deducted when generating from the meal plan. */
@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val quantity: Double? = null,
    val unit: MeasureUnit? = null,
)

/** "Sara är i affären": visible to the family while someone is shopping. Document id: uid. */
@Serializable
data class ShoppingPresence(
    val memberId: String,
    val listId: String,
    val startedAt: Instant,
    val until: Instant,
)

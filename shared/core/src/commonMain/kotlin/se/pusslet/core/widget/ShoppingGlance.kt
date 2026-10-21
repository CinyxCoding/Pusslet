package se.pusslet.core.widget

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.Store
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.ShoppingText

/** What the Inköpslistan widget shows (ADR-039): the active list and what is left to buy, in the store's order. */
data class ShoppingGlance(val listId: String, val listName: String, val items: List<GlanceItem>)

/** One thing to buy, as the list writes it ("2 l Mjölk (laktosfri)"). */
data class GlanceItem(val id: String, val text: String)

object ShoppingGlances {
    /** The list chosen on this phone, else the first grocery list, else the first; null without lists (none is made here). */
    fun activeList(lists: List<ShoppingList>, selectedId: String?): ShoppingList? =
        lists.firstOrNull { it.id == selectedId } ?: lists.firstOrNull { it.kind == ListKind.Grocery } ?: lists.firstOrNull()

    /** [list]'s open items in aisle order: the list's store, else the family's default store, else the default order. */
    fun from(
        list: ShoppingList,
        items: List<ShoppingItem>,
        stores: List<Store> = emptyList(),
        defaultStoreId: String? = null,
    ): ShoppingGlance {
        val store = (list.storeId ?: defaultStoreId)?.let { id -> stores.firstOrNull { it.id == id } }
        val open = ShoppingListGenerator.groupForStore(items.filter { it.listId == list.id }, store?.aisleOrder ?: Aisle.defaultOrder)
            .flatMap { it.items }
        return ShoppingGlance(list.id, list.name.trim(), open.map { GlanceItem(it.id, ShoppingText.line(it)) })
    }
}

package se.pusslet.android.widget

import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.ShoppingQuickAdd
import se.pusslet.ui.format.quantityText
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.widget_not_set_up
import se.pusslet.ui.resources.widget_shopping_add
import se.pusslet.ui.resources.widget_shopping_empty
import se.pusslet.ui.resources.widget_shopping_item

data class ShoppingWidgetItem(val id: String, val text: String)

data class ShoppingWidgetUi(
    val title: String,
    val listId: String? = null,
    /** Unchecked items in the store's walking order. */
    val items: List<ShoppingWidgetItem> = emptyList(),
    val empty: String = "",
    val addLabel: String = "",
    val setUp: Boolean = true,
)

/** The list last open on this phone, in the store's order – the same list the Handla tab opens on. */
class ShoppingWidgetContent(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val quickAdd: ShoppingQuickAdd,
    private val sessions: SessionProvider,
) {
    suspend fun load(): ShoppingWidgetUi {
        sessions.loaded.first { it }
        val list = quickAdd.currentList()
        if (sessions.session.value.familyId == null || list == null) {
            return ShoppingWidgetUi(title = getString(Res.string.widget_not_set_up), setUp = false)
        }
        val stores = shopping.stores.first()
        val store = stores.firstOrNull { it.id == list.storeId } ?: stores.firstOrNull { it.id == family.settings.first().defaultStoreId }
        val open = shopping.getItems(list.id).filter { !it.checked }
        val ordered = ShoppingListGenerator.groupForStore(open, store?.aisleOrder ?: Aisle.defaultOrder).flatMap { it.items }
        return ShoppingWidgetUi(
            title = list.name,
            listId = list.id,
            items = ordered.take(MAX_ITEMS).map { ShoppingWidgetItem(it.id, label(it)) },
            empty = getString(Res.string.widget_shopping_empty),
            addLabel = getString(Res.string.widget_shopping_add),
        )
    }

    /** "Gurka · 2 st" */
    private suspend fun label(item: ShoppingItem): String {
        val amount = item.amount ?: return item.name
        return getString(Res.string.widget_shopping_item, item.name, quantityText(amount))
    }

    private companion object {
        /** A widget is a glance; the full list is one tap away. */
        const val MAX_ITEMS = 60
    }
}

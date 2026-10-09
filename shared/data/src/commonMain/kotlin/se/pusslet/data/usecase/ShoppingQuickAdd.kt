package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.shopping.AisleClassifier
import se.pusslet.core.shopping.ShoppingInput
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/** Typing onto a shopping list – the same rules in the app and the home-screen widget. */
class ShoppingQuickAdd(
    private val shopping: ShoppingRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The list last open on this phone, else the first one. */
    suspend fun currentList(): ShoppingList? {
        val lists = shopping.lists.first()
        val active = prefs.get(Prefs.ActiveShoppingList)
        return lists.firstOrNull { it.id == active } ?: lists.firstOrNull()
    }

    /** "mjölk, 2 gurka och pasta" -> three items, each in its aisle (the family's own corrections win). Returns the names added. */
    suspend fun add(listId: String, text: String): List<String> {
        val overrides = shopping.aisleOverrides.first()
        val open = shopping.getItems(listId).filter { !it.checked }
        val added = mutableListOf<String>()
        for (p in ShoppingInput.parse(text)) {
            // Typing "mjölk" when milk is already on the list does not add a second line.
            val existing = open.firstOrNull { ShoppingNames.canonical(it.name) == ShoppingNames.canonical(p.name) }
            if (existing != null && p.quantity == null) continue
            shopping.saveItem(
                ShoppingItem(
                    id = ids.newId(),
                    listId = listId,
                    name = p.name,
                    quantity = p.quantity?.value,
                    unit = p.quantity?.unit,
                    aisle = AisleClassifier.classify(p.name, overrides),
                    addedBy = sessions.session.value.memberId,
                    addedAt = clock.now(),
                ),
            )
            added += p.name
        }
        return added
    }
}

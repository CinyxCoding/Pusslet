package se.pusslet.data.usecase

import kotlinx.coroutines.flow.first
import se.pusslet.core.meals.RecipeScaler
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.shopping.AisleClassifier
import se.pusslet.core.shopping.ProductCategories
import se.pusslet.core.shopping.ShoppingInput
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import kotlin.time.Clock

/**
 * What one add changed, so it can be undone: new lines, and the earlier version of lines that were merged into.
 * [names] are the item names as they now read on the list (for "Tillagt: Mjölk, Bröd").
 */
data class AddOutcome(
    val created: List<String> = emptyList(),
    val previous: List<ShoppingItem> = emptyList(),
    val names: List<String> = emptyList(),
) {
    val count: Int get() = created.size + previous.size
    val isEmpty: Boolean get() = count == 0
}

/** A scanned code: the product when Open Food Facts knows it, and what was added. */
data class ScanOutcome(val barcode: String, val product: ProductInfo?, val added: AddOutcome)

/**
 * Adding to a shopping list from the list's own field, a staple suggestion or the barcode scanner. Items that are
 * already on the list are merged instead of duplicated (see [ShoppingInput.mergeInto]); the aisle is guessed from the
 * family's corrections, the word list and (for scanned products) Open Food Facts categories.
 */
class ShoppingAdder(
    private val shopping: ShoppingRepository,
    private val products: ProductLookup,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The list chosen on this phone, else the first grocery list, else a new "Veckohandling". */
    suspend fun activeListId(): String {
        val lists = shopping.lists.first()
        val selected = prefs.get(Prefs.ActiveShoppingList)
        lists.firstOrNull { it.id == selected }?.let { return it.id }
        (lists.firstOrNull { it.kind == ListKind.Grocery } ?: lists.firstOrNull())?.let { return it.id }
        val list = ShoppingList(ids.newId(), DEFAULT_LIST_NAME)
        shopping.saveList(list)
        prefs.set(Prefs.ActiveShoppingList, list.id)
        return list.id
    }

    /** "Lägg på inköpslistan" from a recipe: scaled to [servings], salt/pepper/water and optional extras skipped. */
    suspend fun addRecipe(recipe: Recipe, servings: Int, listId: String? = null): AddOutcome {
        val items = RecipeScaler.scale(recipe, servings)
            .filter { !it.optional && !ShoppingNames.isStaple(it.name) }
            .map { ParsedItem(it.name.trim().replaceFirstChar { c -> c.uppercase() }, it.amount) }
        return addItems(listId ?: activeListId(), items)
    }

    suspend fun addText(listId: String, text: String): AddOutcome = addItems(listId, ShoppingInput.parse(text))

    suspend fun addItems(listId: String, items: List<ParsedItem>, barcode: String? = null, hint: ProductInfo? = null): AddOutcome {
        if (items.isEmpty()) return AddOutcome()
        val overrides = shopping.aisleOverrides.first()
        val existing = shopping.getItems(listId).toMutableList()
        val created = mutableListOf<String>()
        val previous = mutableListOf<ShoppingItem>()
        val names = mutableListOf<String>()
        for (parsed in items) {
            val merged = ShoppingInput.mergeInto(existing, parsed)
            if (merged != null) {
                names += merged.name
                val before = existing.first { it.id == merged.id }
                if (merged != before) {
                    shopping.saveItem(merged)
                    if (previous.none { it.id == before.id }) previous += before
                    existing[existing.indexOf(before)] = merged
                }
                continue
            }
            val byName = AisleClassifier.classify(parsed.name, overrides)
            val byCategory = hint?.let { ProductCategories.aisle(it.categories) }
            val item = ShoppingItem(
                id = ids.newId(),
                listId = listId,
                name = parsed.name,
                quantity = parsed.quantity?.value,
                unit = parsed.quantity?.unit,
                aisle = if (byName == Aisle.Other) byCategory ?: byName else byName,
                addedBy = sessions.session.value.memberId,
                addedAt = clock.now(),
                barcode = barcode,
            )
            shopping.saveItem(item)
            existing += item
            created += item.id
            names += item.name
        }
        return AddOutcome(created, previous, names.distinct())
    }

    /** Looks the code up (cached, works offline for known codes) and adds the product. Unknown codes add nothing. */
    suspend fun addScanned(listId: String, barcode: String): ScanOutcome {
        val code = barcode.filter { it.isDigit() }
        // Scanning the same package twice means "two of these".
        val known = shopping.getItems(listId).firstOrNull { it.barcode == code && !it.checked }
        if (known != null && (known.unit == null || known.unit == MeasureUnit.St)) {
            val updated = known.copy(quantity = (known.quantity ?: 1.0) + 1.0, unit = known.unit ?: MeasureUnit.St)
            shopping.saveItem(updated)
            return ScanOutcome(code, null, AddOutcome(previous = listOf(known), names = listOf(known.name)))
        }
        val product = products.product(code) ?: return ScanOutcome(code, null, AddOutcome())
        val name = displayName(product) ?: return ScanOutcome(code, product, AddOutcome())
        return ScanOutcome(code, product, addItems(listId, listOf(ParsedItem(name)), barcode = code, hint = product))
    }

    /** For a code Open Food Facts does not know: the user types the name, the code is remembered on the item. */
    suspend fun addNamed(listId: String, barcode: String, name: String): AddOutcome =
        addItems(listId, ShoppingInput.parse(name).take(1), barcode = barcode.filter { it.isDigit() })

    suspend fun undo(outcome: AddOutcome) {
        outcome.created.forEach { shopping.deleteItem(it) }
        shopping.saveItems(outcome.previous)
    }

    private fun displayName(p: ProductInfo): String? {
        val name = p.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val brand = p.brand?.trim()?.takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
        return listOfNotNull(brand, name).joinToString(" ")
    }

    private companion object {
        const val DEFAULT_LIST_NAME = "Veckohandling"
    }
}

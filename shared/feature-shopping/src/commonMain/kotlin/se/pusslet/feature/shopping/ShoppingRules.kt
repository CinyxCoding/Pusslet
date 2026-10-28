package se.pusslet.feature.shopping

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.util.SwedishOrder
import se.pusslet.data.repository.PrefKey

/** Device-local preferences that only Handla reads (stored like `Prefs`, never synced). */
internal object ShoppingPrefs {
    /**
     * Planned meals ("date/recipeId") the family has dealt with: bought and cleared from the list, or waved away with
     * "Inte nu" on "Tacos saknas på listan". Pruned to the last [HANDLED_MEALS_KEEP_DAYS] days on every write.
     */
    val HandledMeals = PrefKey("shopping.handledMeals", SetSerializer(String.serializer()), emptySet())
}

/**
 * Hands the "Ångra" for "Klar – töm vagnen" from store mode to the list underneath: store mode closes right away and the
 * list shows "6 varor rensade · Ångra". One per app (a Koin single); it holds at most one receipt.
 */
class ShoppingReceipts {
    private val pending = MutableStateFlow<List<ShoppingItem>?>(null)

    /** Each posted receipt once, to the first collector that sees it. */
    val receipts: Flow<List<ShoppingItem>> = pending.transform { items ->
        if (items != null && pending.compareAndSet(items, null)) emit(items)
    }

    fun post(items: List<ShoppingItem>) {
        if (items.isNotEmpty()) pending.value = items
    }
}

/** The key of one planned meal in [ShoppingPrefs.HandledMeals] and [WeekMissing.meals]. */
internal fun mealKey(date: LocalDate, recipeId: String): String = "$date/$recipeId"

/** The planned meals the generator made [items] for ("till Tacos fre"). */
internal fun mealKeys(items: List<ShoppingItem>): Set<String> = items.flatMap { it.sources }.mapNotNullTo(HashSet()) { source ->
    val recipe = source.recipeId ?: return@mapNotNullTo null
    source.mealDate?.let { mealKey(it, recipe) }
}

/** Drops handled meals older than a week, so the stored set stays small. Keys that do not parse are dropped too. */
internal fun pruneHandled(keys: Set<String>, today: LocalDate): Set<String> {
    val oldest = today.minus(DatePeriod(days = HANDLED_MEALS_KEEP_DAYS))
    return keys.filterTo(HashSet()) { key ->
        val date = LocalDate.Formats.ISO.parseOrNull(key.substringBefore('/'))
        date != null && date >= oldest
    }
}

/**
 * Recipes planned from [today] to Sunday that nothing on the list was made for and that the family has not dealt with
 * ([handled]). Only grocery lists ask; leftovers ("rester") and meals without a recipe need no shopping. The card stays
 * about this week even though "Handla till veckan" can pick any days: its default days are the same, today to Sunday.
 */
internal fun weekMissing(
    list: ShoppingList?,
    items: List<ShoppingItem>,
    plan: MealPlanWeek?,
    today: LocalDate,
    handled: Set<String>,
): WeekMissing? {
    if (list == null || list.kind != ListKind.Grocery || plan == null) return null
    val onList = mealKeys(items)
    val missing = plan.entries.values
        .filter { ShoppingListGenerator.needsShopping(it) && it.date >= today && it.date in plan.week }
        .mapNotNull { entry -> entry.recipeId?.let { mealKey(entry.date, it) to entry } }
        .filter { (key, _) -> key !in onList && key !in handled }
        .sortedBy { (_, entry) -> entry.date }
    if (missing.isEmpty()) return null
    return WeekMissing(
        week = plan.week,
        titles = missing.map { (_, entry) -> entry.title.trim() }.filter { it.isNotEmpty() }.distinct(),
        meals = missing.mapTo(LinkedHashSet()) { (key, _) -> key },
    )
}

/** Everyone in [others] (already without the viewer) as one row, first to start first. Null when nobody is shopping. */
internal fun shoppersRow(
    others: List<ShoppingPresence>,
    people: Map<String, FamilyPerson>,
    lists: List<ShoppingList>,
    shownListId: String?,
): ShoppersRow? {
    if (others.isEmpty()) return null
    val ordered = others.sortedBy { it.startedAt }
    return ShoppersRow(
        names = ordered.mapNotNull { people[it.memberId]?.name }.distinct(),
        elsewhere = ordered.map { it.listId }.distinct().filter { it != shownListId }.mapNotNull { id -> lists.find { it.id == id } },
    )
}

/**
 * The open [groups] plus items ticked off a moment ago ([lingering], with `checked = true`), each in its own aisle and in
 * name order, as before the tick. A ticked row stays struck through for a moment before it moves to "Avbockat".
 */
internal fun withLingering(groups: List<AisleGroup>, lingering: Collection<ShoppingItem>, aisleOrder: List<Aisle>): List<AisleGroup> {
    val open = groups.flatMap { it.items }
    val openIds = open.mapTo(HashSet()) { it.id }
    val extra = lingering.filter { it.id !in openIds }
    if (extra.isEmpty()) return groups
    val rank = (aisleOrder + Aisle.entries.filter { it !in aisleOrder }).withIndex().associate { it.value to it.index }
    return (open + extra)
        .groupBy { it.aisle }
        .toList()
        .sortedBy { (aisle, _) -> rank[aisle] ?: Int.MAX_VALUE }
        .map { (aisle, items) -> AisleGroup(aisle, items.sortedBy { SwedishOrder.key(it.name) }) }
}

private const val HANDLED_MEALS_KEEP_DAYS = 7

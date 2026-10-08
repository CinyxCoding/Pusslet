package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ItemSource
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.units.Dimension
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Quantity
import se.pusslet.core.units.Units
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Instant

/** One planned meal that needs ingredients. */
data class MealDemand(val entry: MealEntry, val recipe: Recipe, val servings: Int)

/** One merged line ready for the shopping list. */
data class GeneratedLine(
    val key: String,
    val name: String,
    /** First amount is the main one; the rest could not be converted ("2 st + 1 dl"). */
    val quantities: List<Quantity>,
    val aisle: Aisle,
    val sources: List<ItemSource>,
) {
    val primary: Quantity? get() = quantities.firstOrNull()
    val extra: String get() = quantities.drop(1).joinToString(" + ") { Units.format(it) }
}

data class ShoppingPlan(val add: List<ShoppingItem>, val update: List<ShoppingItem>, val remove: List<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
}

data class AisleGroup(val aisle: Aisle, val items: List<ShoppingItem>)

/**
 * Meal plan -> shopping list. Merges the same item across recipes ("1 gul lök" + "2 gula lökar" = 3 st),
 * converts compatible units (2 msk + 1 dl grädde = 1,3 dl), skips salt/pepper/water, deducts what is at home and
 * can be re-run: manual additions and checked items are left alone, earlier generated amounts are replaced.
 */
object ShoppingListGenerator {
    private const val EPSILON = 0.001

    fun demands(entries: List<MealEntry>, recipes: Map<String, Recipe>, defaultServings: Int): List<MealDemand> = entries
        .filter { it.leftoversFrom == null }
        .mapNotNull { e ->
            val recipe = e.recipeId?.let(recipes::get) ?: return@mapNotNull null
            val servings = (e.servings ?: defaultServings) * (if (e.cookDouble) 2 else 1)
            MealDemand(e, recipe, servings)
        }

    private class Accumulator(val name: String, val aisle: Aisle) {
        val mass = mutableListOf<Double>()
        val volume = mutableListOf<Double>()
        val counts = LinkedHashMap<MeasureUnit, Double>()
        val order = mutableListOf<Dimension>()
        val sources = mutableListOf<ItemSource>()

        fun add(q: Quantity?) {
            val unit = q?.unit ?: return
            if (unit.dimension !in order) order += unit.dimension
            val base = q.value * unit.baseFactor
            when (unit.dimension) {
                Dimension.Mass -> mass += base
                Dimension.Volume -> volume += base
                Dimension.Count -> {
                    counts[unit] = (counts[unit] ?: 0.0) + q.value
                }
            }
        }

        fun quantities(): List<Quantity> = order.flatMap { dim ->
            when (dim) {
                Dimension.Mass -> listOf(Units.normalize(Quantity(mass.sum(), MeasureUnit.G)))
                Dimension.Volume -> listOf(Units.normalize(Quantity(volume.sum(), MeasureUnit.Ml)))
                // You buy whole onions and whole cans.
                Dimension.Count -> counts.map { (unit, value) -> Quantity(ceil(value - EPSILON), unit) }
            }
        }
    }

    fun lines(
        demands: List<MealDemand>,
        pantry: List<PantryItem> = emptyList(),
        aisleOverrides: Map<String, Aisle> = emptyMap(),
        includeStaples: Boolean = false,
    ): List<GeneratedLine> {
        val acc = LinkedHashMap<String, Accumulator>()
        for (d in demands) {
            val factor = d.servings.toDouble() / d.recipe.servings.coerceAtLeast(1)
            for (ing in d.recipe.ingredients) {
                if (!includeStaples && (ing.optional || ShoppingNames.isStaple(ing.name))) continue
                val key = ShoppingNames.canonical(ing.name)
                if (key.isBlank()) continue
                val a = acc.getOrPut(key) { Accumulator(ing.name.trim(), AisleClassifier.classify(ing.name, aisleOverrides)) }
                val scaled = ing.amount?.scaled(factor)
                a.add(scaled)
                a.sources += ItemSource(d.recipe.id, d.entry.date, d.recipe.title, scaled?.value, scaled?.unit)
            }
        }
        val pantryByKey = pantry.groupBy { ShoppingNames.canonical(it.name) }
        return acc.mapNotNull { (key, a) ->
            var quantities = a.quantities()
            pantryByKey[key]?.let { have ->
                // "Hemma" without an amount means: we have enough.
                if (have.any { it.quantity == null }) return@mapNotNull null
                quantities = deduct(quantities, have.map { Quantity(requireNotNull(it.quantity), it.unit ?: MeasureUnit.St) })
                if (quantities.isEmpty()) return@mapNotNull null
            }
            GeneratedLine(key, a.name, quantities, a.aisle, a.sources)
        }
    }

    private fun deduct(need: List<Quantity>, have: List<Quantity>): List<Quantity> = need.mapNotNull { q ->
        val unit = q.unit ?: return@mapNotNull q
        val available = have.mapNotNull { it.convertTo(unit)?.value }.sum()
        val rest = q.value - available
        if (rest <= EPSILON) null else q.copy(value = if (unit.dimension == Dimension.Count) ceil(rest - EPSILON) else rest)
    }

    /**
     * Works out what to add, update and remove on [listId] so it matches [lines] for the meals in [dates].
     * Generated shares are tracked per source, so "2 dl mjölk" someone typed by hand stays when the plan changes.
     */
    fun plan(
        listId: String,
        existing: List<ShoppingItem>,
        lines: List<GeneratedLine>,
        dates: ClosedRange<LocalDate>,
        newId: () -> String,
        now: Instant,
        memberId: String?,
    ): ShoppingPlan {
        fun generated(s: ItemSource) = s.mealDate != null && s.recipeId != null && s.mealDate in dates
        val inList = existing.filter { it.listId == listId }
        val open = inList.filter { !it.checked }
        val bought = inList.filter { it.checked }
            .flatMap { it.sources }
            .filter(::generated)
            .map { it.recipeId to it.mealDate }
            .toSet()
        val handled = mutableSetOf<String>()
        val add = mutableListOf<ShoppingItem>()
        val update = mutableListOf<ShoppingItem>()

        for (line in lines) {
            // Already in the cart from an earlier generation for the very same meals.
            if (line.sources.all { (it.recipeId to it.mealDate) in bought }) continue
            val match = open.firstOrNull { it.id !in handled && ShoppingNames.canonical(it.name) == line.key }
            val primary = line.primary
            if (match == null) {
                add += ShoppingItem(
                    id = newId(),
                    listId = listId,
                    name = line.name,
                    quantity = primary?.value,
                    unit = primary?.unit,
                    note = line.extra,
                    aisle = line.aisle,
                    addedBy = memberId,
                    addedAt = now,
                    sources = line.sources,
                )
            } else {
                handled += match.id
                val merged = mergeInto(match, line, ::generated)
                if (merged != match) update += merged
            }
        }

        val remove = mutableListOf<String>()
        for (item in open) {
            if (item.id in handled || item.sources.none(::generated)) continue
            val keep = item.sources.filterNot(::generated)
            val manual = manualPart(item, ::generated)
            if (keep.isEmpty() && (item.quantity == null || manual <= EPSILON)) {
                remove += item.id
            } else {
                val trimmed = item.copy(quantity = if (item.quantity == null) null else manual, sources = keep)
                if (trimmed != item) update += trimmed
            }
        }
        return ShoppingPlan(add, update, remove)
    }

    /**
     * "Lägg ingredienserna på listan" for one recipe, outside the meal plan: amounts are added to matching open items
     * (converted when possible) and the lines count as hand-added, so regenerating the week never removes them.
     */
    fun append(
        listId: String,
        existing: List<ShoppingItem>,
        lines: List<GeneratedLine>,
        newId: () -> String,
        now: Instant,
        memberId: String?,
    ): ShoppingPlan {
        val open = existing.filter { it.listId == listId && !it.checked }
        val add = mutableListOf<ShoppingItem>()
        val update = mutableListOf<ShoppingItem>()
        for (line in lines) {
            val sources = line.sources.map { it.copy(mealDate = null) }
            val primary = line.primary
            val match = open.firstOrNull { ShoppingNames.canonical(it.name) == line.key }
            val converted = primary?.let { p ->
                match?.let { m ->
                    if (m.unit == null &&
                        m.quantity == null
                    ) {
                        p
                    } else {
                        p.convertTo(m.unit ?: p.unit ?: MeasureUnit.St)
                    }
                }
            }
            if (match != null && (primary == null || converted != null)) {
                val current = update.firstOrNull { it.id == match.id } ?: match
                val merged = current.copy(
                    quantity = when {
                        converted == null -> current.quantity
                        current.quantity == null -> converted.value
                        else -> current.quantity + converted.value
                    },
                    unit = current.unit ?: converted?.unit,
                    sources = current.sources + sources,
                )
                update.removeAll { it.id == match.id }
                update += merged
            } else {
                add += ShoppingItem(
                    id = newId(),
                    listId = listId,
                    name = line.name,
                    quantity = primary?.value,
                    unit = primary?.unit,
                    note = line.extra,
                    aisle = line.aisle,
                    addedBy = memberId,
                    addedAt = now,
                    sources = sources,
                )
            }
        }
        return ShoppingPlan(add, update, emptyList())
    }

    /** Existing item + freshly generated line: hand-typed share kept, generated share replaced. */
    private fun mergeInto(match: ShoppingItem, line: GeneratedLine, generated: (ItemSource) -> Boolean): ShoppingItem {
        val primary = line.primary
        val untouched = match.unit == null && match.quantity == null
        val quantity = when {
            primary == null -> match.quantity
            untouched -> primary.value
            else -> primary.convertTo(match.unit ?: primary.unit ?: MeasureUnit.St)?.let { manualPart(match, generated) + it.value }
                ?: match.quantity
        }
        val note = listOfNotNull(match.note.takeIf { it.isNotBlank() && it != line.extra }, line.extra.takeIf { it.isNotBlank() })
        return match.copy(
            quantity = quantity,
            unit = if (untouched) primary?.unit else match.unit ?: primary?.unit,
            note = note.distinct().joinToString(" · "),
            sources = match.sources.filterNot(generated) + line.sources,
        )
    }

    /** The part of an item's amount that was not generated (typed by someone). */
    private fun manualPart(item: ShoppingItem, generated: (ItemSource) -> Boolean): Double {
        val total = item.quantity ?: return 0.0
        val unit = item.unit ?: return total
        val generatedAmount = item.sources.filter(generated).sumOf { s ->
            s.quantity?.let { Quantity(it, s.unit ?: MeasureUnit.St).convertTo(unit)?.value } ?: 0.0
        }
        val rest = total - generatedAmount
        return if (abs(rest) < EPSILON) 0.0 else rest.coerceAtLeast(0.0)
    }

    /** Store mode: aisles in the store's walking order, unchecked first; checked items go last ("i vagnen"). */
    fun groupForStore(items: List<ShoppingItem>, aisleOrder: List<Aisle> = Aisle.defaultOrder): List<AisleGroup> {
        val rank = (aisleOrder + Aisle.entries.filter { it !in aisleOrder }).withIndex().associate { it.value to it.index }
        return items.filter { !it.checked }
            .groupBy { it.aisle }
            .toList()
            .sortedBy { (aisle, _) -> rank[aisle] ?: Int.MAX_VALUE }
            .map { (aisle, list) -> AisleGroup(aisle, list.sortedBy { it.name.lowercase() }) }
    }
}

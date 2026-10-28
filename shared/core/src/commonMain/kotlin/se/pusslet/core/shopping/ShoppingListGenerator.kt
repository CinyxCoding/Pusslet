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
import se.pusslet.core.util.SwedishOrder
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
    /**
     * "Finns hemma?": the pantry has this but not an amount we can count with (no amount noted, an amount in a unit that
     * does not convert, or the recipe gives no amount). The line is still made, and the family can untick it.
     */
    val askIfAtHome: Boolean = false,
    /** What the pantry covered, already taken off [quantities] ("2 dl hemma"). */
    val fromPantry: List<Quantity> = emptyList(),
) {
    val primary: Quantity? get() = quantities.firstOrNull()
    val extra: String get() = quantities.drop(1).joinToString(" + ") { Units.format(it) }
}

/** The lines for the list, plus what the pantry covered completely (shown as "Räcker hemma: Ris"). */
data class Generation(val lines: List<GeneratedLine>, val coveredAtHome: List<String>)

data class ShoppingPlan(val add: List<ShoppingItem>, val update: List<ShoppingItem>, val remove: List<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
}

data class AisleGroup(val aisle: Aisle, val items: List<ShoppingItem>)

/**
 * Meal plan -> shopping list. Merges the same item across recipes ("1 gul lök" + "2 gula lökar" = 3 st),
 * converts compatible units (2 msk + 1 dl grädde = 1,3 dl), skips salt/pepper/water, deducts known amounts at home and
 * can be re-run: manual additions and checked items are left alone, earlier generated amounts are replaced. The pantry
 * is only read, never changed.
 */
object ShoppingListGenerator {
    private const val EPSILON = 0.001

    /**
     * Whether a planned meal needs anything from the store: a recipe that is cooked at home that day. Hämtmat and "Ingen
     * matlagning" ([se.pusslet.core.model.MealKind]), leftovers ("rester") and meals without a recipe (free text) add
     * nothing, even if an old document still carries a recipe id.
     */
    fun needsShopping(entry: MealEntry): Boolean = entry.cooksRecipe

    fun demands(entries: List<MealEntry>, recipes: Map<String, Recipe>, defaultServings: Int): List<MealDemand> = entries
        .filter(::needsShopping)
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
    ): List<GeneratedLine> = generate(demands, pantry, aisleOverrides, includeStaples).lines

    /**
     * Merged lines for [demands] with the pantry applied. Items that are "slut" count as not at home. Known amounts are
     * deducted where the unit converts; a line the pantry covers completely is left out and named in
     * [Generation.coveredAtHome]. Everything else that is at home is kept and marked [GeneratedLine.askIfAtHome].
     */
    fun generate(
        demands: List<MealDemand>,
        pantry: List<PantryItem> = emptyList(),
        aisleOverrides: Map<String, Aisle> = emptyMap(),
        includeStaples: Boolean = false,
    ): Generation {
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
        val atHome = pantry.filter { it.inStock }.groupBy { ShoppingNames.canonical(it.name) }
        val covered = mutableListOf<String>()
        val lines = acc.mapNotNull { (key, a) ->
            val need = a.quantities()
            val have = atHome[key] ?: return@mapNotNull GeneratedLine(key, a.name, need, a.aisle, a.sources)
            val known = have.mapNotNull { it.amount }
            val usable = known.filter { h -> need.any { n -> n.unit?.let(h::convertTo) != null } }
            val rest = deduct(need, usable)
            if (rest.isEmpty() && need.isNotEmpty()) {
                // The amounts noted at home are enough.
                covered += a.name
                return@mapNotNull null
            }
            // Some at home but nothing to count with: no amount noted, a unit that does not convert, or no amount needed.
            val unsure = have.any { it.amountUnknown } || usable.size < known.size || need.isEmpty()
            GeneratedLine(key, a.name, rest, a.aisle, a.sources, askIfAtHome = unsure, fromPantry = usable)
        }
        return Generation(lines, covered)
    }

    private fun deduct(need: List<Quantity>, have: List<Quantity>): List<Quantity> = need.mapNotNull { q ->
        val unit = q.unit ?: return@mapNotNull q
        val available = have.mapNotNull { it.convertTo(unit)?.value }.sum()
        val rest = q.value - available
        if (rest <= EPSILON) null else q.copy(value = if (unit.dimension == Dimension.Count) ceil(rest - EPSILON) else rest)
    }

    /** [plan] for a whole range of days, such as one ISO week. */
    fun plan(
        listId: String,
        existing: List<ShoppingItem>,
        lines: List<GeneratedLine>,
        dates: ClosedRange<LocalDate>,
        newId: () -> String,
        now: Instant,
        memberId: String?,
    ): ShoppingPlan = plan(listId, existing, lines, { it in dates }, newId, now, memberId)

    /**
     * [plan] for chosen days, which need not follow each other (Monday, Wednesday and Friday). Shares generated for any
     * other day are left exactly as they are, so shopping for Monday to Wednesday and later for Thursday to Sunday adds up.
     */
    fun plan(
        listId: String,
        existing: List<ShoppingItem>,
        lines: List<GeneratedLine>,
        days: Set<LocalDate>,
        newId: () -> String,
        now: Instant,
        memberId: String?,
    ): ShoppingPlan = plan(listId, existing, lines, { it in days }, newId, now, memberId)

    /**
     * Works out what to add, update and remove on [listId] so it matches [lines] for the meals on the days [inScope]
     * accepts. Generated shares are tracked per source (recipe and date), so running it twice changes nothing, and
     * "2 dl mjölk" someone typed by hand stays when the plan changes. Items nobody generated are never removed.
     */
    private fun plan(
        listId: String,
        existing: List<ShoppingItem>,
        lines: List<GeneratedLine>,
        inScope: (LocalDate) -> Boolean,
        newId: () -> String,
        now: Instant,
        memberId: String?,
    ): ShoppingPlan {
        fun generated(s: ItemSource) = s.mealDate != null && s.recipeId != null && inScope(s.mealDate)
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
        val sources = match.sources.filterNot(generated) + line.sources
        return match.copy(
            quantity = quantity,
            unit = if (untouched) primary?.unit else match.unit ?: primary?.unit,
            note = note.distinct().joinToString(" · "),
            // The same shares in another order (other days were shopped for in between) are no change.
            sources = if (sources.size == match.sources.size && sources.toSet() == match.sources.toSet()) match.sources else sources,
        )
    }

    /** The part of an item's amount that was not generated (typed by someone). */
    private fun manualPart(item: ShoppingItem, generated: (ItemSource) -> Boolean): Double {
        val total = item.quantity ?: return 0.0
        val unit = item.unit ?: return total
        val shares = item.sources.filter(generated).sumOf { s ->
            s.quantity?.let { Quantity(it, s.unit ?: MeasureUnit.St).convertTo(unit)?.value } ?: 0.0
        }
        // Whole pieces were bought for the shares together (½ + ½ påse = 1 påse), as in the generated line.
        val generatedAmount = if (unit.dimension == Dimension.Count && shares > 0) ceil(shares - EPSILON) else shares
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
            .map { (aisle, list) -> AisleGroup(aisle, list.sortedBy { SwedishOrder.key(it.name) }) }
    }
}

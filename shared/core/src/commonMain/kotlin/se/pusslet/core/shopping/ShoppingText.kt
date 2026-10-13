package se.pusslet.core.shopping

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.units.Units

/** Plain-text versions of a list, for "Dela som text" to any app. Labels come from the UI so the text is translated. */
object ShoppingText {
    /** "2 l Mjölk (laktosfri)" */
    fun line(item: ShoppingItem): String {
        val amount = item.amount?.let { Units.format(it) }
        val name = listOfNotNull(amount, item.name.trim()).joinToString(" ")
        return if (item.note.isBlank()) name else "$name (${item.note.trim()})"
    }

    /** Title, then one block per aisle in store order. Ticked-off items are left out. */
    fun share(title: String, groups: List<AisleGroup>, aisleLabel: (Aisle) -> String): String = buildString {
        append(title.trim())
        for (group in groups) {
            if (group.items.isEmpty()) continue
            append("\n\n").append(aisleLabel(group.aisle))
            group.items.forEach { append("\n• ").append(line(it)) }
        }
    }
}

/**
 * Open Food Facts category tags ("en:dairies", "en:frozen-foods") to a store department, used when a scanned
 * product's name does not tell the aisle. Most specific first.
 */
object ProductCategories {
    private val rules: List<Pair<String, Aisle>> = listOf(
        "frozen" to Aisle.Frozen,
        "ice-cream" to Aisle.Frozen,
        "baby" to Aisle.Baby,
        "cheese" to Aisle.Cheese,
        "egg" to Aisle.Eggs,
        "yogurt" to Aisle.Dairy,
        "dair" to Aisle.Dairy,
        "milk" to Aisle.Dairy,
        "butter" to Aisle.Dairy,
        "breakfast-cereal" to Aisle.Breakfast,
        "coffee" to Aisle.Breakfast,
        "tea" to Aisle.Breakfast,
        "juice" to Aisle.Breakfast,
        "crispbread" to Aisle.Pantry,
        "bread" to Aisle.Bakery,
        "fish" to Aisle.Fish,
        "seafood" to Aisle.Fish,
        "sausage" to Aisle.Meat,
        "meat" to Aisle.Meat,
        "poultr" to Aisle.Meat,
        "hams" to Aisle.Deli,
        "spread" to Aisle.Deli,
        "fruit" to Aisle.Produce,
        "vegetable" to Aisle.Produce,
        "canned" to Aisle.Canned,
        "sauce" to Aisle.SpicesAndSauces,
        "condiment" to Aisle.SpicesAndSauces,
        "spice" to Aisle.SpicesAndSauces,
        "oil" to Aisle.SpicesAndSauces,
        "sugar" to Aisle.Baking,
        "flour" to Aisle.Pantry,
        "pasta" to Aisle.Pantry,
        "rice" to Aisle.Pantry,
        "cereal" to Aisle.Pantry,
        "chocolate" to Aisle.SnacksAndSweets,
        "candi" to Aisle.SnacksAndSweets,
        "confectioner" to Aisle.SnacksAndSweets,
        "snack" to Aisle.SnacksAndSweets,
        "chips" to Aisle.SnacksAndSweets,
        "biscuit" to Aisle.SnacksAndSweets,
        "water" to Aisle.Drinks,
        "soda" to Aisle.Drinks,
        "beverage" to Aisle.Drinks,
        "drink" to Aisle.Drinks,
        "pet-food" to Aisle.Pets,
        "hygiene" to Aisle.Hygiene,
        "cleaning" to Aisle.Household,
    )

    fun aisle(categories: List<String>): Aisle? {
        // OFF lists categories from general to specific; the most specific tag decides.
        for (tag in categories.asReversed().map { it.lowercase().substringAfter(':') }) {
            val words = tag.split('-')
            rules.firstOrNull { (stem, _) -> matches(stem, tag, words) }?.let { return it.second }
        }
        return null
    }

    /** Word-start matching, so "tea" finds "green-teas" but not "beef-steaks". */
    private fun matches(stem: String, tag: String, words: List<String>): Boolean =
        if ('-' in stem) stem in tag else words.any { it.startsWith(stem) }
}

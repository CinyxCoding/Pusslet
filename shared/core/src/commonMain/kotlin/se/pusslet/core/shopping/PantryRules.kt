package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.quickadd.ParsedItem
import kotlin.time.Clock

/** How close an item at home is to its best-before date. */
enum class BestBefore {
    /** More than [PantryRules.SOON_DAYS] days left. */
    Later,

    /** "Går ut snart": today or within [PantryRules.SOON_DAYS] days. */
    Soon,

    /** The date has passed (best before is not "bad after", so it is a reminder, never a problem). */
    Passed,
}

/** One storage place at home and what is kept there, in stock first. */
data class PantryGroup(val location: PantryLocation, val items: List<PantryItem>)

/** Rules for "Det vi har hemma": grouping, best-before reminders and adding typed text. */
object PantryRules {
    /** "Går ut snart" covers today and the next three days. */
    const val SOON_DAYS = 3

    fun today(clock: Clock, zone: TimeZone): LocalDate = clock.now().toLocalDateTime(zone).date

    /** Null when there is no date, or when the item is "slut" (nothing at home to remind about). */
    fun bestBefore(item: PantryItem, today: LocalDate): BestBefore? {
        val date = item.bestBefore ?: return null
        if (!item.inStock) return null
        val left = today.daysUntil(date)
        return when {
            left < 0 -> BestBefore.Passed
            left <= SOON_DAYS -> BestBefore.Soon
            else -> BestBefore.Later
        }
    }

    /** Skafferi, Kyl, Frys (empty places left out); in each, what is at home by name, then what is "slut". */
    fun grouped(items: List<PantryItem>): List<PantryGroup> = PantryLocation.entries.mapNotNull { location ->
        val here = items.filter { it.location == location }
        if (here.isEmpty()) {
            null
        } else {
            PantryGroup(location, here.sortedWith(compareBy<PantryItem> { !it.inStock }.thenBy { it.name.lowercase() }))
        }
    }

    /** A first guess at where something typed is kept: dairy and meat in the fridge, frozen food in the freezer. */
    fun guessLocation(name: String, aisleOverrides: Map<String, Aisle> = emptyMap()): PantryLocation =
        location(AisleClassifier.classify(name, aisleOverrides))

    /** Where things from [aisle] are usually kept. */
    fun location(aisle: Aisle): PantryLocation = when (aisle) {
        Aisle.Dairy, Aisle.Cheese, Aisle.Eggs, Aisle.Meat, Aisle.Fish, Aisle.Deli -> PantryLocation.Fridge
        Aisle.Frozen -> PantryLocation.Freezer
        else -> PantryLocation.Pantry
    }

    /**
     * "1 kg mjöl, ris" typed into the pantry: something already noted (same name, also when "slut") gets the new amount
     * and is at home again, keeping its place and date; new things get a guessed place.
     */
    fun add(
        existing: List<PantryItem>,
        parsed: ParsedItem,
        newId: () -> String,
        aisleOverrides: Map<String, Aisle> = emptyMap(),
        barcode: String? = null,
        /** A better guess than the name gives, e.g. from Open Food Facts categories. */
        location: PantryLocation? = null,
    ): PantryItem {
        val key = ShoppingNames.canonical(parsed.name)
        val match = existing.firstOrNull { (barcode != null && it.barcode == barcode) || ShoppingNames.canonical(it.name) == key }
        return PantryItem(
            id = match?.id ?: newId(),
            name = match?.name ?: parsed.name,
            quantity = parsed.quantity?.value,
            unit = parsed.quantity?.unit,
            location = match?.location ?: location ?: guessLocation(parsed.name, aisleOverrides),
            bestBefore = match?.bestBefore,
            inStock = true,
            barcode = barcode ?: match?.barcode,
        )
    }
}

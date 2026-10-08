package se.pusslet.core.shopping

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** One checked-off item in the purchase history (kept on the device only). */
data class Purchase(val name: String, val at: Instant)

/** "Mjölk brukar ni köpa var 4:e dag – senast för 5 dagar sedan." */
data class StapleSuggestion(
    val key: String,
    val name: String,
    val typicalInterval: Duration,
    val lastBought: Instant,
    val timesBought: Int,
)

/**
 * Basvaror: items the family buys regularly are suggested when they are about due again. Rule based and explainable:
 * at least three purchases on different days, a typical (median) interval of at most four weeks, and the last purchase
 * at least ~90 % of that interval ago. Items already on the list or dismissed recently are left out.
 */
object StapleSuggester {
    private const val MIN_PURCHASES = 3
    private val maxInterval = 28.days
    private val minInterval = 1.days
    private const val DUE_FACTOR = 0.9

    /** Purchases closer than this are the same shopping trip (two packs of milk checked one by one). */
    private val sameTrip = 12.hours

    fun suggest(
        history: List<Purchase>,
        now: Instant,
        onList: Set<String> = emptySet(),
        dismissed: Set<String> = emptySet(),
        limit: Int = 6,
    ): List<StapleSuggestion> {
        val skip = (onList + dismissed).map(ShoppingNames::canonical).toSet()
        return history
            .groupBy { ShoppingNames.canonical(it.name) }
            .filterKeys { it.isNotBlank() && it !in skip }
            .mapNotNull { (key, purchases) -> suggestion(key, purchases, now) }
            .sortedWith(compareByDescending<Pair<StapleSuggestion, Double>> { it.second }.thenBy { it.first.name })
            .take(limit)
            .map { it.first }
    }

    /** Pair of suggestion and how overdue it is (1.0 = exactly due). */
    private fun suggestion(key: String, purchases: List<Purchase>, now: Instant): Pair<StapleSuggestion, Double>? {
        val trips = trips(purchases.sortedBy { it.at })
        if (trips.size < MIN_PURCHASES) return null
        val intervals = trips.zipWithNext { a, b -> b.at - a.at }
        val typical = median(intervals)
        if (typical < minInterval || typical > maxInterval) return null
        val last = trips.last()
        val since = now - last.at
        val due = since / typical
        if (due < DUE_FACTOR) return null
        // Far past due means the family stopped buying it; do not nag forever.
        if (due > STALE_FACTOR) return null
        val display = purchases.groupingBy { it.name.trim() }.eachCount().maxBy { it.value }.key
        return StapleSuggestion(key, display, typical, last.at, trips.size) to due
    }

    private fun trips(sorted: List<Purchase>): List<Purchase> {
        val result = mutableListOf<Purchase>()
        for (p in sorted) {
            val prev = result.lastOrNull()
            if (prev == null || p.at - prev.at >= sameTrip) result += p
        }
        return result
    }

    private fun median(values: List<Duration>): Duration {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private const val STALE_FACTOR = 4.0
}

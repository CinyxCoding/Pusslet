package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/** One ticked-off item from the local purchase history. */
data class PurchaseRecord(val name: String, val at: Instant)

/** "Brukar ni köpa mjölk ungefär var 4:e dag – senast för 5 dagar sedan." */
data class StapleSuggestion(
    val key: String,
    val name: String,
    val intervalDays: Int,
    val daysSinceLast: Int,
    val lastBought: Instant,
)

/**
 * Basvaror: things the family buys again and again are suggested when it is about time, based only on the local
 * purchase history (no AI, ADR-001). An item needs [MIN_PURCHASES] purchases on different days; it is "due" when
 * most of its usual interval has passed. Items not bought for a long time (several intervals) are considered
 * dropped and not nagged about.
 */
object StapleSuggester {
    const val MIN_PURCHASES = 3
    const val LOOKBACK_DAYS = 120
    private const val DUE_SHARE = 0.85
    private const val DROPPED_AFTER_INTERVALS = 4

    /**
     * @param onList names already on the list (open items); they are never suggested.
     * @param dismissed key -> when the user waved the suggestion away; hidden until the item is bought again.
     */
    fun suggest(
        history: List<PurchaseRecord>,
        onList: Collection<String>,
        today: LocalDate,
        zone: TimeZone,
        dismissed: Map<String, Instant> = emptyMap(),
        limit: Int = 6,
    ): List<StapleSuggestion> {
        val listed = onList.map(ShoppingNames::canonical).toSet()
        return history
            .groupBy { ShoppingNames.canonical(it.name) }
            .filterKeys { it.isNotBlank() && it !in listed }
            .mapNotNull { (key, records) -> evaluate(key, records, today, zone, dismissed[key]) }
            .sortedByDescending { it.daysSinceLast.toDouble() / it.intervalDays }
            .take(limit)
    }

    private fun evaluate(
        key: String,
        records: List<PurchaseRecord>,
        today: LocalDate,
        zone: TimeZone,
        dismissedAt: Instant?,
    ): StapleSuggestion? {
        val latest = records.maxBy { it.at }
        if (dismissedAt != null && dismissedAt > latest.at) return null
        val days = records.map { it.at.toLocalDateTime(zone).date.toEpochDays() }.distinct().sorted()
        if (days.size < MIN_PURCHASES) return null
        val interval = median(days.zipWithNext { a, b -> b - a }).coerceAtLeast(1)
        val since = (today.toEpochDays() - days.last()).toInt()
        val dueAfter = (interval * DUE_SHARE).roundToInt().coerceAtLeast(1)
        if (since < dueAfter || since > interval * DROPPED_AFTER_INTERVALS) return null
        return StapleSuggestion(key, latest.name.trim(), interval, since, latest.at)
    }

    private fun median(values: List<Long>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val m = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        return m.roundToInt()
    }
}

package se.pusslet.core.costs

import se.pusslet.core.model.ExpenseCategory
import se.pusslet.core.model.SharedExpense

/**
 * Delade kostnader mellan hemmen (ADR-057), as data. Rules, no money moves through the app: it keeps the list both homes
 * agree on and says who owes whom, and a payment made outside the app (Swish, bank) is written down as a settlement.
 */
object SharedCosts {
    /** Positive: the other home owes [myHome]; negative: [myHome] owes the other home. In öre. */
    fun balance(expenses: List<SharedExpense>, myHome: String): Long = expenses.sumOf { e ->
        val share = otherShare(e)
        if (e.paidByHome == myHome) share else -share
    }

    /** What the home that did not pay owes for [e], rounded to whole öre. */
    fun otherShare(e: SharedExpense): Long = (e.amountOre * e.otherSharePercent.toLong().coerceIn(0, MAX_PERCENT) + HALF) / MAX_PERCENT

    /** The settlement that evens the balance out: paid by the home that owes, all of it to the other home. */
    fun settle(balanceForMe: Long, myHome: String, otherHome: String): Pair<String, Long>? = when {
        balanceForMe > 0 -> otherHome to balanceForMe
        balanceForMe < 0 -> myHome to -balanceForMe
        else -> null
    }

    /** Whether [myHome] may remove [e]: the home that added a cost is the one that removes it (ADR-059, as the rules say). */
    fun canRemove(e: SharedExpense, myHome: String?): Boolean = e.addedByHome == null || e.addedByHome == myHome

    /** Spent per category, settlements left out, biggest first ("Kläder 1 450 kr"). */
    fun byCategory(expenses: List<SharedExpense>): List<Pair<ExpenseCategory, Long>> = expenses.filter { !it.settlement }
        .groupBy { it.category }
        .map { (c, list) -> c to list.sumOf { it.amountOre } }
        .sortedByDescending { it.second }

    /** "899", "899,50", "1 450" with Swedish digit grouping; [decimal] is the language's separator. */
    fun format(ore: Long, decimal: Char = ',', group: Char = NO_BREAK_SPACE): String {
        val sign = if (ore < 0) "-" else ""
        val abs = kotlin.math.abs(ore)
        val kr = (abs / ORE_PER_KR).toString().reversed().chunked(THOUSANDS).joinToString(group.toString()).reversed()
        val rest = abs % ORE_PER_KR
        return if (rest == 0L) "$sign$kr" else "$sign$kr$decimal${rest.toString().padStart(2, '0')}"
    }

    /** "899", "899,50", "899.5" → öre; null when it is not an amount (0 kr is not one either). */
    fun parse(text: String): Long? {
        val clean = text.trim().removeSuffix("kr").trim().replace(" ", "").replace(NO_BREAK_SPACE.toString(), "").replace(',', '.')
        val value = clean.takeIf { AMOUNT.matches(it) }?.toDoubleOrNull() ?: return null
        if (value <= 0 || value > MAX_KR) return null
        return kotlin.math.round(value * ORE_PER_KR).toLong()
    }

    private val AMOUNT = Regex("""\d+(\.\d{1,2})?""")

    /** Between thousands in Swedish ("1 450 kr"), so the number never breaks across lines. */
    const val NO_BREAK_SPACE = ' '

    const val ORE_PER_KR = 100L
    private const val MAX_PERCENT = 100L
    private const val HALF = 50L
    private const val THOUSANDS = 3
    private const val MAX_KR = 1_000_000.0
}

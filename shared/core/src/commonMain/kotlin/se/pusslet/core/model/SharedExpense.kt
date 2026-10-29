package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * A cost for a child the two homes share (ADR-057): "Vinterjacka 899 kr, Sara betalade, hälften var". It lives in the
 * space shared with the other home, so both homes see the same list. A payment between the homes ("Lina swishade 450 kr")
 * is the same thing with [settlement] set and the other home's whole share: the balance needs no other rule.
 */
@Serializable
data class SharedExpense(
    val id: String,
    val title: String,
    /** Öre, so sums never round ("899,50 kr" = 89950). */
    val amountOre: Long,
    val date: LocalDate,
    /** The home that paid: a family id listed in the space. */
    val paidByHome: String,
    /** The adult who paid, for "Sara betalade". */
    val paidBy: String? = null,
    /** How much of it the other home pays, in percent: 50 = half each, 100 = all of it (a settlement always). */
    val otherSharePercent: Int = 50,
    val category: ExpenseCategory = ExpenseCategory.Other,
    val childIds: List<String> = emptyList(),
    val settlement: Boolean = false,
    val note: String = "",
    /** The adult who added it, for "inlagd av Lina". */
    val createdBy: String? = null,
    /** The home that added it: only that home removes it (ADR-059). Null on costs from before that rule. */
    val addedByHome: String? = null,
)

@Serializable
enum class ExpenseCategory {
    Clothes,
    Activities,
    School,
    Health,
    Other,
}

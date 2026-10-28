package se.pusslet.core.costs

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.ExpenseCategory
import se.pusslet.core.model.SharedExpense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Delade kostnader (ADR-057): Sara's home ("lind") and Lina's home ("berg") share Ella. */
class SharedCostsTest {
    private val day = LocalDate(2026, 10, 20)

    private fun cost(kr: Long, paidBy: String, share: Int = 50, category: ExpenseCategory = ExpenseCategory.Other) =
        SharedExpense("e$kr$paidBy", "Sak", kr * 100, day, paidBy, otherSharePercent = share, category = category)

    @Test
    fun theBalanceSaysWhoOwesWhom() {
        val jacket = cost(899, "lind", category = ExpenseCategory.Clothes)
        val football = cost(1200, "berg", category = ExpenseCategory.Activities)
        val dentist = cost(300, "lind", share = 100, category = ExpenseCategory.Health)
        // Lina owes half the jacket (449,50) and the whole dentist (300); Sara owes half the football (600).
        assertEquals(44_950L + 30_000L - 60_000L, SharedCosts.balance(listOf(jacket, football, dentist), "lind"))
        assertEquals(
            -(44_950L + 30_000L - 60_000L),
            SharedCosts.balance(listOf(jacket, football, dentist), "berg"),
            "the same seen from Lina",
        )
        assertEquals(0L, SharedCosts.balance(listOf(cost(500, "lind", share = 0)), "lind"), "the payer covers it")
    }

    @Test
    fun aSettlementEvensItOut() {
        val costs = listOf(cost(899, "lind"))
        val balance = SharedCosts.balance(costs, "lind")
        val (payer, amount) = SharedCosts.settle(balance, "lind", "berg") ?: error("Lina owes")
        assertEquals("berg" to 44_950L, payer to amount)
        val settlement = SharedExpense("s", "Reglering", amount, day, payer, otherSharePercent = 100, settlement = true)
        assertEquals(0L, SharedCosts.balance(costs + settlement, "lind"))
        assertNull(SharedCosts.settle(0, "lind", "berg"))
        assertEquals(listOf(ExpenseCategory.Other to 89_900L), SharedCosts.byCategory(costs + settlement), "settlements are not spending")
    }

    @Test
    fun amountsAreWrittenAndReadTheSwedishWay() {
        assertEquals("1 450", SharedCosts.format(145_000))
        assertEquals("899,50", SharedCosts.format(89_950))
        assertEquals("-12", SharedCosts.format(-1_200))
        assertEquals(89_950L, SharedCosts.parse("899,50"))
        assertEquals(89_950L, SharedCosts.parse("899.5 kr"))
        assertEquals(145_000L, SharedCosts.parse("1 450"))
        assertNull(SharedCosts.parse("0"))
        assertNull(SharedCosts.parse("åttahundra"))
        assertNull(SharedCosts.parse("12,345"))
    }
}

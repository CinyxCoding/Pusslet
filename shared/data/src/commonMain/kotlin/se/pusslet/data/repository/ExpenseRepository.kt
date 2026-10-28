package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import se.pusslet.core.costs.SharedCosts
import se.pusslet.core.model.ExpenseCategory
import se.pusslet.core.model.SharedExpense
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore

/** Costs shared with the other home (ADR-057). They live in the space both homes share, never in one home's own data. */
class ExpenseRepository(
    private val docs: DocumentStore,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
) {
    /** The space's costs and settlements, newest first. */
    fun expenses(spaceId: String): Flow<List<SharedExpense>> = docs.observeIn(spaceId, Collections.Expenses, SharedExpense.serializer())
        .map { list -> list.sortedWith(compareByDescending<SharedExpense> { it.date }.thenByDescending { it.id }) }

    @Suppress("LongParameterList")
    suspend fun add(
        spaceId: String,
        title: String,
        amountOre: Long,
        date: LocalDate,
        paidByHome: String,
        otherSharePercent: Int,
        category: ExpenseCategory,
        childIds: List<String>,
    ): SharedExpense {
        val me = sessions.session.value.memberId
        val expense = SharedExpense(
            id = ids.newId(),
            title = title.trim(),
            amountOre = amountOre,
            date = date,
            paidByHome = paidByHome,
            paidBy = me.takeIf { paidByHome == sessions.session.value.familyId },
            otherSharePercent = otherSharePercent.coerceIn(0, 100),
            category = category,
            childIds = childIds,
            createdBy = me,
        )
        docs.put(Collections.Expenses, expense.id, expense, SharedExpense.serializer(), familyId = spaceId)
        return expense
    }

    /** "Reglera": the home that owes pays the whole balance (Swish, bank); written down so both homes see it even. */
    suspend fun settle(spaceId: String, title: String, date: LocalDate, otherHome: String, balanceForMe: Long): SharedExpense? {
        val myHome = sessions.session.value.familyId ?: return null
        val (payer, amount) = SharedCosts.settle(balanceForMe, myHome, otherHome) ?: return null
        val settlement = SharedExpense(
            id = ids.newId(),
            title = title,
            amountOre = amount,
            date = date,
            paidByHome = payer,
            paidBy = sessions.session.value.memberId.takeIf { payer == myHome },
            otherSharePercent = 100,
            settlement = true,
            createdBy = sessions.session.value.memberId,
        )
        docs.put(Collections.Expenses, settlement.id, settlement, SharedExpense.serializer(), familyId = spaceId)
        return settlement
    }

    suspend fun delete(id: String) = docs.delete(Collections.Expenses, id)
}

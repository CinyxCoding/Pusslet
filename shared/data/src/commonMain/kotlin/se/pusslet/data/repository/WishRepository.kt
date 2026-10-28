package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.pusslet.core.model.WishItem
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock

/** The children's wishlists (BRIEF §4.8, ADR-054): shared with the family and other adults so grandparents know what to give. */
class WishRepository(
    private val docs: DocumentStore,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    val wishes: Flow<List<WishItem>> = docs.observeAll(Collections.Wishes, WishItem.serializer())

    fun forChild(childId: String): Flow<List<WishItem>> = wishes.map { all ->
        all.filter { it.childId == childId }.sortedWith(compareBy({ it.taken }, { it.addedAt }))
    }

    suspend fun add(childId: String, text: String, link: String = ""): WishItem? {
        val clean = text.trim().takeIf { it.isNotEmpty() } ?: return null
        val wish = WishItem(ids.newId(), childId, clean.take(MAX_TEXT), link.trim(), addedAt = clock.now())
        docs.put(Collections.Wishes, wish.id, wish, WishItem.serializer())
        return wish
    }

    /** "Köpt" – by me, so two grandparents do not buy the same thing. */
    suspend fun setTaken(wish: WishItem, taken: Boolean) {
        val me = sessions.session.value.memberId
        docs.update(Collections.Wishes, wish.id, WishItem.serializer()) { it.copy(taken = taken, takenBy = if (taken) me else null) }
    }

    suspend fun delete(id: String) = docs.delete(Collections.Wishes, id)

    private companion object {
        const val MAX_TEXT = 300
    }
}

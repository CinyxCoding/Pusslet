package se.pusslet.data.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.today.TodayPlanner
import se.pusslet.core.widget.ShoppingGlance
import se.pusslet.core.widget.ShoppingGlances
import se.pusslet.core.widget.TodayGlance
import se.pusslet.core.widget.TodayGlances
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.TodayInputSource
import kotlin.time.Clock

/**
 * The home-screen widgets' data (ADR-039), from Room like Idag and Handla (offline first). A widget reads [today] or
 * [shopping] each time it is drawn; [changes] tells the running app when to draw them again. Both are null without a
 * family on this phone. Idag's calendar filter applies, as on Idag; weather and Idag's own layout do not.
 */
class WidgetSource(
    private val today: TodayInputSource,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    suspend fun today(): TodayGlance? {
        if (!hasFamily()) return null
        return TodayGlances.from(TodayPlanner.build(today.current(TodayInputSource.Layers.Shown)))
    }

    /** The list Handla shows on this phone; null without a family. A family without lists gets an empty glance (no list id). */
    suspend fun shopping(): ShoppingGlance? {
        if (!hasFamily()) return null
        val list = ShoppingGlances.activeList(shopping.lists.first(), prefs.get(Prefs.ActiveShoppingList).ifEmpty { null })
            ?: return ShoppingGlance(listId = "", listName = "", items = emptyList())
        return ShoppingGlances.from(list, shopping.items.first(), shopping.stores.first(), family.settings.first().defaultStoreId)
    }

    /** A tick in the widget: bought, as in Handla (it moves to "Handlat" there). */
    suspend fun setBought(itemId: String) {
        val item = shopping.items.first().firstOrNull { it.id == itemId && !it.checked } ?: return
        shopping.setChecked(item, checked = true)
    }

    /** Emits whenever something a widget shows may have changed: the family, events, packing, requests or the list. */
    fun changes(): Flow<Unit> {
        val date = clock.now().toLocalDateTime(zone).date
        val todayChanges = combine(
            sessions.session,
            today.household,
            today.occurrences(date),
            today.extras,
            today.requests,
        ) { _, _, _, _, _ -> }
        val listChanges = combine(shopping.lists, shopping.items, prefs.observe(Prefs.ActiveShoppingList)) { _, _, _ -> }
        return combine(todayChanges, listChanges) { _, _ -> }
    }

    private suspend fun hasFamily(): Boolean {
        sessions.loaded.first { it }
        return sessions.session.value.hasFamily
    }
}

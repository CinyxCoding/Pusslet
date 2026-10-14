package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs

/** Stable ids of the first-time hints (UX plan 4.7). At most one per screen; never rename a shipped id. */
object HintIds {
    /** Idag: "Lägg till med +. Skriv som du pratar." */
    val TodayAdd = "today.add"

    /** Handla, demo family: "Tips: mjölk två gånger blir en rad". */
    val ShoppingMerge = "shopping.merge"

    /** The helper view's first start: "Här ser du när du hämtar och lämnar." */
    val HelperHome = "helper.home"

    val all = setOf(TodayAdd, ShoppingMerge, HelperHome)
}

/** A hint shows once per phone: it stays until "Jag förstår" and does not come back after a restart. */
class FirstTimeHints(private val prefs: PreferencesRepository) {
    fun shouldShow(id: String): Flow<Boolean> = prefs.observe(Prefs.SeenHints).map { id !in it }.distinctUntilChanged()

    suspend fun dismiss(id: String) = prefs.update(Prefs.SeenHints) { it + id }

    /** Screenshot tests and "Visa första start igen" start from a known state. */
    suspend fun dismissAll() = prefs.update(Prefs.SeenHints) { it + HintIds.all }
}

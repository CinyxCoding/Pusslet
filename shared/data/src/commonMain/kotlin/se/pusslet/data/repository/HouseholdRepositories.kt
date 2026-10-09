package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import se.pusslet.core.meals.MealPlanner
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.FavoriteWeek
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.model.WishItem
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class PackingRepository(private val docs: DocumentStore) {
    val rules: Flow<List<PackingRule>> = docs.observeAll(Collections.PackingRules, PackingRule.serializer())
    val checks: Flow<Map<String, PackingChecks>> = docs.observeAll(Collections.PackingChecks, PackingChecks.serializer())
        .map { list -> list.associateBy { it.docId } }

    suspend fun saveRule(rule: PackingRule) = docs.put(Collections.PackingRules, rule.id, rule, PackingRule.serializer())

    suspend fun deleteRule(id: String) = docs.delete(Collections.PackingRules, id)

    suspend fun setChecked(date: LocalDate, childId: String, itemKey: String, checked: Boolean) {
        val id = PackingChecks.docId(date, childId)
        val current = docs.get(Collections.PackingChecks, id, PackingChecks.serializer()) ?: PackingChecks(date, childId)
        val next = current.copy(checked = if (checked) current.checked + (itemKey to true) else current.checked - itemKey)
        docs.put(Collections.PackingChecks, id, next, PackingChecks.serializer())
    }
}

class MealRepository(private val docs: DocumentStore) {
    val weeks: Flow<List<MealPlanWeek>> = docs.observeAll(Collections.MealPlans, MealPlanWeek.serializer())
    val favorites: Flow<List<FavoriteWeek>> = docs.observeAll(Collections.FavoriteWeeks, FavoriteWeek.serializer())

    fun week(week: IsoWeek): Flow<MealPlanWeek> = docs.observe(Collections.MealPlans, week.key, MealPlanWeek.serializer()).map {
        it
            ?: MealPlanWeek(week)
    }

    suspend fun getWeek(week: IsoWeek): MealPlanWeek =
        docs.get(Collections.MealPlans, week.key, MealPlanWeek.serializer()) ?: MealPlanWeek(week)

    suspend fun setEntry(entry: MealEntry) {
        val week = entry.date.isoWeek
        val plan = getWeek(week).with(entry)
        docs.put(Collections.MealPlans, week.key, plan, MealPlanWeek.serializer())
        // "Gör dubbelt": leftovers tomorrow – dinner if it is free, otherwise a lunch box.
        if (entry.cookDouble) {
            val next = entry.date.plusDays(1)
            val nextWeek = next.isoWeek
            val nextPlan = getWeek(nextWeek)
            val alreadyThere = MealSlot.entries.any { nextPlan.entry(next, it)?.leftoversFrom == entry.date }
            val leftovers = MealPlanner.leftoversFor(entry, nextDinnerTaken = nextPlan.entry(next, MealSlot.Dinner) != null)
            if (!alreadyThere && nextPlan.entry(next, leftovers.slot) == null) {
                docs.put(Collections.MealPlans, nextWeek.key, nextPlan.with(leftovers), MealPlanWeek.serializer())
            }
        }
    }

    suspend fun clearEntry(date: LocalDate, slot: MealSlot) {
        val week = date.isoWeek
        docs.put(Collections.MealPlans, week.key, getWeek(week).without(date, slot), MealPlanWeek.serializer())
    }

    /** Moves (drag and drop) or swaps two days. */
    suspend fun move(from: LocalDate, to: LocalDate, slot: MealSlot = MealSlot.Dinner) {
        val a = getWeek(from.isoWeek).entry(from, slot)
        val b = getWeek(to.isoWeek).entry(to, slot)
        if (a == null) return
        clearEntry(from, slot)
        setEntry(a.copy(date = to, cookDouble = false))
        if (b != null) setEntry(b.copy(date = from, cookDouble = false))
    }

    /** "Kopiera förra veckan". Keeps already planned days. */
    suspend fun copyWeek(source: IsoWeek, target: IsoWeek) {
        val src = getWeek(source)
        var dst = getWeek(target)
        val offset = (target.monday.toEpochDays() - source.monday.toEpochDays()).toInt()
        for (e in src.entries.values) {
            val date = e.date.plusDays(offset)
            if (dst.entry(date, e.slot) == null) dst = dst.with(e.copy(date = date, leftoversFrom = null))
        }
        docs.put(Collections.MealPlans, target.key, dst, MealPlanWeek.serializer())
    }

    suspend fun saveFavorite(fav: FavoriteWeek) = docs.put(Collections.FavoriteWeeks, fav.id, fav, FavoriteWeek.serializer())

    suspend fun deleteFavorite(id: String) = docs.delete(Collections.FavoriteWeeks, id)

    /**
     * Days in [fav] replace those days; doubled dinners plan their leftovers like when set by hand. Applied from Sunday
     * backwards, so a doubled Monday sees that Tuesday's dinner is planned and becomes a lunch box instead.
     */
    suspend fun applyFavorite(fav: FavoriteWeek, target: IsoWeek) {
        for ((offset, e) in fav.days.entries.sortedByDescending { it.key }) {
            setEntry(e.copy(date = target.monday.plusDays(offset), leftoversFrom = null))
        }
    }
}

class RecipeRepository(private val docs: DocumentStore) {
    val recipes: Flow<List<Recipe>> = docs.observeAll(Collections.Recipes, Recipe.serializer()).map {
        it.sortedBy { r ->
            r.title.lowercase()
        }
    }

    fun recipe(id: String): Flow<Recipe?> = docs.observe(Collections.Recipes, id, Recipe.serializer())

    suspend fun get(id: String): Recipe? = docs.get(Collections.Recipes, id, Recipe.serializer())

    suspend fun all(): List<Recipe> = docs.getAll(Collections.Recipes, Recipe.serializer())

    suspend fun save(recipe: Recipe) = docs.put(Collections.Recipes, recipe.id, recipe, Recipe.serializer())

    suspend fun delete(id: String) = docs.delete(Collections.Recipes, id)

    /** Remembered ingredient name -> Livsmedelsverket food number (confirmed by the user). */
    val ingredientMappings: Flow<Map<String, Int>> = docs.observeAll(Collections.IngredientMappings, IngredientMapping.serializer())
        .map { list -> list.associate { it.name to it.foodNumber } }

    suspend fun rememberMapping(name: String, foodNumber: Int) {
        val key = name.trim().lowercase()
        docs.put(
            Collections.IngredientMappings,
            key.hashCode().toUInt().toString(16),
            IngredientMapping(key, foodNumber),
            IngredientMapping.serializer(),
        )
    }
}

@Serializable
data class IngredientMapping(val name: String, val foodNumber: Int)

@Serializable
data class AisleOverride(val name: String, val aisle: Aisle)

class ShoppingRepository(
    private val docs: DocumentStore,
    private val purchases: PurchaseLogDao,
    private val sessions: SessionProvider,
    private val clock: Clock = Clock.System,
) {
    val lists: Flow<List<ShoppingList>> = docs.observeAll(Collections.ShoppingLists, ShoppingList.serializer()).map {
        it.sortedBy { l ->
            l.sortIndex
        }
    }
    val items: Flow<List<ShoppingItem>> = docs.observeAll(Collections.ShoppingItems, ShoppingItem.serializer())
    val stores: Flow<List<Store>> = docs.observeAll(Collections.Stores, Store.serializer())
    val pantry: Flow<List<PantryItem>> = docs.observeAll(Collections.Pantry, PantryItem.serializer())
    val presence: Flow<List<ShoppingPresence>> = docs.observeAll(Collections.Presence, ShoppingPresence.serializer())
    val aisleOverrides: Flow<Map<String, Aisle>> = docs.observeAll(Collections.AisleOverrides, AisleOverride.serializer())
        .map { list -> list.associate { it.name to it.aisle } }

    fun itemsIn(listId: String): Flow<List<ShoppingItem>> = items.map { all -> all.filter { it.listId == listId } }

    suspend fun saveList(list: ShoppingList) = docs.put(Collections.ShoppingLists, list.id, list, ShoppingList.serializer())

    suspend fun deleteList(id: String) {
        docs.getAll(Collections.ShoppingItems, ShoppingItem.serializer()).filter {
            it.listId == id
        }.forEach { docs.delete(Collections.ShoppingItems, it.id) }
        docs.delete(Collections.ShoppingLists, id)
    }

    suspend fun saveItem(item: ShoppingItem) = docs.put(Collections.ShoppingItems, item.id, item, ShoppingItem.serializer())

    suspend fun saveItems(items: List<ShoppingItem>) = items.forEach { saveItem(it) }

    suspend fun deleteItem(id: String) = docs.delete(Collections.ShoppingItems, id)

    suspend fun getItems(listId: String): List<ShoppingItem> = docs.getAll(Collections.ShoppingItems, ShoppingItem.serializer()).filter {
        it.listId ==
            listId
    }

    suspend fun setChecked(item: ShoppingItem, checked: Boolean) {
        val me = sessions.session.value.memberId
        val now = clock.now()
        saveItem(item.copy(checked = checked, checkedAt = if (checked) now else null, checkedBy = if (checked) me else null))
        if (checked) {
            val fid = sessions.session.value.familyId ?: return
            purchases.insert(
                PurchaseLogEntity(
                    familyId = fid,
                    normalizedName = item.name.trim().lowercase(),
                    displayName = item.name,
                    boughtAt = now.toEpochMilliseconds(),
                ),
            )
        }
    }

    /** Removes checked items from a list ("Rensa avbockade"). */
    suspend fun clearChecked(listId: String) = getItems(listId).filter { it.checked }.forEach { deleteItem(it.id) }

    suspend fun saveStore(store: Store) = docs.put(Collections.Stores, store.id, store, Store.serializer())

    suspend fun deleteStore(id: String) = docs.delete(Collections.Stores, id)

    suspend fun savePantry(item: PantryItem) = docs.put(Collections.Pantry, item.id, item, PantryItem.serializer())

    suspend fun deletePantry(id: String) = docs.delete(Collections.Pantry, id)

    /** "Lär sig av användarens korrigeringar": the corrected aisle is remembered for the whole family. */
    suspend fun rememberAisle(name: String, aisle: Aisle) {
        val key = name.trim().lowercase()
        docs.put(Collections.AisleOverrides, key.hashCode().toUInt().toString(16), AisleOverride(key, aisle), AisleOverride.serializer())
    }

    suspend fun startShopping(listId: String, minutes: Int = 60) {
        val me = sessions.session.value.memberId ?: return
        val now = clock.now()
        docs.put(Collections.Presence, me, ShoppingPresence(me, listId, now, now + minutes.minutes), ShoppingPresence.serializer())
    }

    suspend fun stopShopping() {
        val me = sessions.session.value.memberId ?: return
        docs.delete(Collections.Presence, me)
    }

    suspend fun purchaseHistory(sinceMillis: Long): List<PurchaseLogEntity> {
        val fid = sessions.session.value.familyId ?: return emptyList()
        return purchases.since(fid, sinceMillis)
    }
}

class TaskRepository(private val docs: DocumentStore) {
    val tasks: Flow<List<FamilyTask>> = docs.observeAll(Collections.Tasks, FamilyTask.serializer())

    suspend fun save(task: FamilyTask) = docs.put(Collections.Tasks, task.id, task, FamilyTask.serializer())

    suspend fun get(id: String): FamilyTask? = docs.get(Collections.Tasks, id, FamilyTask.serializer())

    suspend fun delete(id: String) = docs.delete(Collections.Tasks, id)

    suspend fun update(id: String, transform: (FamilyTask) -> FamilyTask) = docs.update(
        Collections.Tasks,
        id,
        FamilyTask.serializer(),
        transform,
    )
}

/** The children's wishlists (BRIEF §4.8): shared with the family and other adults so grandparents know what to give. */
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

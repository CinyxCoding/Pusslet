package se.pusslet.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.PurchaseRecord
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.shopping.ShoppingPlan
import se.pusslet.core.shopping.StapleSuggester
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.IdGenerator
import se.pusslet.core.util.SwedishOrder
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.AddOutcome
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HintIds
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Someone else in the family is in the store right now ("Sara är i affären"). */
data class Shopper(val memberId: String, val person: FamilyPerson?, val listName: String?)

/**
 * Everyone else shopping right now, merged into one row (UX plan, Handla 5): "Sara och Johan handlar nu", or with the
 * lists they shop from when another list is showing: "Sara handlar (Apoteket) · Byt".
 */
data class ShoppersRow(
    /** Known names, first to start first. Empty when no name is known ("Någon handlar nu"). */
    val names: List<String>,
    /** Lists other than the shown one that they are shopping from; "Byt" shows the first of them. */
    val elsewhere: List<ShoppingList>,
)

/**
 * "Tacos och Lasagne saknas på listan" (UX plan, Handla 4): recipes planned from today to Sunday that nothing on the list
 * was made for. [meals] are their "date/recipeId" keys; dismissing the card remembers them as handled.
 */
data class WeekMissing(val week: IsoWeek, val titles: List<String>, val meals: Set<String>)

data class ShoppingUiState(
    val loading: Boolean = true,
    val lists: List<ShoppingList> = emptyList(),
    val list: ShoppingList? = null,
    val store: Store? = null,
    val stores: List<Store> = emptyList(),
    /** Open items per aisle, in the store's walking order. */
    val groups: List<AisleGroup> = emptyList(),
    /** Ticked off ("i vagnen"), most recent first. */
    val checked: List<ShoppingItem> = emptyList(),
    val suggestions: List<StapleSuggestion> = emptyList(),
    val shoppers: List<Shopper> = emptyList(),
    val iAmShopping: Boolean = false,
    /** Parents manage lists and stores; helpers can add and tick off items (Firestore rules). */
    val canManage: Boolean = true,
    val people: Map<String, FamilyPerson> = emptyMap(),
    val usePantry: Boolean = false,
    /** [shoppers] as one row ("Sara och Johan handlar nu"); null when nobody else is shopping. */
    val shoppersRow: ShoppersRow? = null,
    /** This week's recipes that are not on the list yet; null when there are none or the card was dismissed. */
    val weekMissing: WeekMissing? = null,
    /**
     * "Tips: mjölk två gånger blir en rad" (UX plan 4.7): the screen's one first-time hint, only in the demo family, only
     * with something on the list and never while shopping. Gone for good after "Jag förstår" ([ShoppingViewModel.dismissMergeHint]).
     */
    val showMergeHint: Boolean = false,
) {
    val openCount: Int get() = groups.sumOf { it.items.size }

    /** Nothing on the list at all, e.g. a brand-new list ("Listan är tom"). */
    val isEmpty: Boolean get() = openCount == 0 && checked.isEmpty()

    /** Everything is ticked off ("Allt är handlat"); a list with nothing on it is [isEmpty] instead. */
    val allBought: Boolean get() = openCount == 0 && checked.isNotEmpty()

    /** "Jag handlar nu" only makes sense while something is left to buy. */
    val canStartShopping: Boolean get() = list != null && openCount > 0

    /** "Handla till veckan" fills grocery lists from the meal plan. */
    val canShopForWeek: Boolean get() = list?.kind != ListKind.Other
}

/** One ingredient in the "Handla till veckan" sheet, with its tick. */
data class IngredientChoice(
    /** [se.pusslet.core.shopping.GeneratedLine.key]: the same ingredient in every recipe. */
    val key: String,
    /** How it would read on the list ("500 g Nötfärs"). */
    val item: ShoppingItem,
    /** Already on the list and gets more ("Ändras"); otherwise a new line ("Läggs till"). */
    val change: Boolean,
    val included: Boolean,
    /** "Finns hemma?": the pantry has some but not an amount to count with, so the family decides. */
    val askIfAtHome: Boolean,
)

/**
 * Preview of "Handla till veckan" for the chosen [days], grouped in the sheet as "Läggs till" and "Ändras" ([choices],
 * each with a tick, plus [trimmed]) and "Tas bort" ([removed], the items behind [ShoppingPlan.remove]). [plan] is what
 * "Uppdatera listan" writes: the ticked ingredients only.
 */
data class MealPlanPreview(
    val days: Set<LocalDate>,
    val plan: ShoppingPlan,
    val removed: List<ShoppingItem> = emptyList(),
    val choices: List<IngredientChoice> = emptyList(),
    /** Lines that only lose a share for a meal that left the plan (typed amounts stay). */
    val trimmed: List<ShoppingItem> = emptyList(),
    /** "Räcker hemma: Ris": what the pantry covers completely. */
    val coveredAtHome: List<String> = emptyList(),
    /** The planned meals on [days] ("date/recipeId"); after "Uppdatera listan" they no longer count as missing. */
    val meals: Set<String> = emptySet(),
    /** The days that can be chosen: today and the 13 after it. */
    val window: List<LocalDate> = emptyList(),
    val today: LocalDate? = null,
) {
    /** Unticked ingredients, by key. */
    val excluded: Set<String> get() = choices.filterNot { it.included }.mapTo(HashSet()) { it.key }

    /** Nothing to tick and nothing to change. */
    val isEmpty: Boolean get() = choices.isEmpty() && plan.isEmpty
}

/** One-off feedback the screen shows as a snackbar or dialog. */
sealed interface ShoppingMessage {
    data class Added(val outcome: AddOutcome) : ShoppingMessage

    data class AlreadyOnList(val names: List<String>) : ShoppingMessage

    data class Removed(val item: ShoppingItem) : ShoppingMessage

    /**
     * Ticked off, offering "Ångra": "Mjölk i vagnen" in store mode (only for the first tick of a visit), "Mjölk bockad" on
     * the planning list. [item] is the item as it was before, so restoring it puts it back in the same aisle.
     */
    data class InCart(val item: ShoppingItem, val inStore: Boolean) : ShoppingMessage

    /** "Rensa avbockade" removed [items] ("6 varor rensade · Ångra"); restoring puts them back exactly as they were. */
    data class Cleared(val items: List<ShoppingItem>) : ShoppingMessage

    /** The last item went into the cart in store mode ("Allt i vagnen!"). */
    data object AllDone : ShoppingMessage

    /** Open Food Facts does not know the code (or we are offline): ask for a name. */
    data class UnknownProduct(val barcode: String) : ShoppingMessage

    data class Generated(val plan: ShoppingPlan) : ShoppingMessage

    data object Failed : ShoppingMessage
}

class ShoppingViewModel(
    private val shopping: ShoppingRepository,
    private val adder: ShoppingAdder,
    private val mealShopping: MealShopping,
    private val meals: MealRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
    /** Outlives this view model, so "slutat handla" is written even while the screen is closing. */
    private val appScope: CoroutineScope,
    private val receipts: ShoppingReceipts,
    private val hints: FirstTimeHints,
    /**
     * Where the list is decoded and put together: off the main thread, so a tick in a list of 600 items never holds up
     * a frame (measured in ShoppingListPerformanceTest). Tests pass their own dispatcher.
     */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val log = Logger.withTag("Shopping")
    private val messageChannel = Channel<ShoppingMessage>(Channel.BUFFERED)
    val messages: Flow<ShoppingMessage> = messageChannel.receiveAsFlow()

    /** "Klar – töm vagnen" in store mode, for the list screen to show with "Ångra" ([restoreCleared]). */
    val storeReceipts: Flow<ShoppingMessage.Cleared> = receipts.receipts.map { ShoppingMessage.Cleared(it) }

    private val history = MutableStateFlow<List<PurchaseRecord>>(emptyList())
    private var startedShopping = false

    /** Store mode offers "Ångra" for the first tick of a visit only; after that the cart itself is the way back. */
    private var undoOfferedThisVisit = false
    private val preview = MutableStateFlow<MealPlanPreview?>(null)
    val mealPlanPreview: StateFlow<MealPlanPreview?> = preview

    /** The latest preview being worked out; a newer choice of days cancels it, so an older answer never wins. */
    private var previewJob: Job? = null

    /**
     * Presence expires by itself ("until") and the week turns, so re-evaluate every minute. The loop only runs while
     * [state] is collected (it is upstream of `stateIn(WhileSubscribed)`).
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(TICK_MS)
        }
    }

    private data class Base(
        val lists: List<ShoppingList>,
        val items: List<ShoppingItem>,
        val stores: List<Store>,
        val settings: FamilySettings,
        val selected: String,
    )

    private data class Social(
        val people: List<FamilyPerson>,
        val presence: List<ShoppingPresence>,
        val session: Session,
        val now: Instant,
    )

    private data class Extras(
        val history: List<PurchaseRecord>,
        val dismissedStaples: Map<String, Long>,
        val weekPlan: MealPlanWeek,
        val handledMeals: Set<String>,
        val mergeHintUnseen: Boolean,
    )

    private val base: Flow<Base> = combine(
        shopping.lists,
        shopping.items,
        shopping.stores,
        family.settings,
        prefs.observe(Prefs.ActiveShoppingList),
    ) { lists, items, stores, settings, selected -> Base(lists, items, stores, settings, selected) }

    private val social: Flow<Social> = combine(family.people, shopping.presence, sessions.session, ticker) { p, pr, s, n ->
        Social(p, pr, s, n)
    }

    /** This week's meal plan, for "Tacos saknas på listan"; follows the week when it turns. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val weekPlan: Flow<MealPlanWeek> = ticker
        .map { today(it).isoWeek }
        .distinctUntilChanged()
        .flatMapLatest { meals.week(it) }

    private val extras: Flow<Extras> = combine(
        history,
        prefs.observe(Prefs.DismissedStaples),
        weekPlan,
        prefs.observe(ShoppingPrefs.HandledMeals),
        hints.shouldShow(HintIds.SHOPPING_MERGE),
    ) { h, dismissed, plan, handled, hint -> Extras(h, dismissed, plan, handled, hint) }

    val state: StateFlow<ShoppingUiState> = combine(base, social, extras) { b, s, e -> build(b, s, e) }
        .flowOn(compute)
        // A failed read (the database closing under the screen) leaves the list as it was instead of crashing the app.
        .catch { e -> log.w(e) { "Shopping list could not be read" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShoppingUiState())

    init {
        // The purchase log grows when something is ticked off; reload it then (it is local and small).
        viewModelScope.launch {
            shopping.items.map { all -> all.count { it.checked } }.distinctUntilChanged().flowOn(compute)
                .catch { e -> log.w(e) { "Shopping list could not be read" } }
                .collect { reloadHistory() }
        }
    }

    private fun today(now: Instant = clock.now()): LocalDate = now.toLocalDateTime(zone).date

    private fun build(b: Base, s: Social, e: Extras): ShoppingUiState {
        val list = activeList(b.lists, b.selected)
        val store = (list?.storeId ?: b.settings.defaultStoreId)?.let { id -> b.stores.firstOrNull { it.id == id } }
        val inList = b.items.filter { it.listId == list?.id }
        val open = inList.filter { !it.checked }
        val me = s.session.memberId
        val live = s.presence.filter { it.until > s.now }
        val iAmShopping = live.any { it.memberId == me }
        val others = live.filter { it.memberId != me }
        val people = s.people.associateBy { it.id }
        val today = today(s.now)
        val suggestions = if (list?.kind == ListKind.Grocery) {
            StapleSuggester.suggest(
                history = e.history,
                onList = open.map { it.name },
                today = today,
                zone = zone,
                dismissed = e.dismissedStaples.mapValues { Instant.fromEpochMilliseconds(it.value) },
            )
        } else {
            emptyList()
        }
        return ShoppingUiState(
            loading = false,
            lists = b.lists,
            list = list,
            store = store,
            stores = b.stores.sortedBy { SwedishOrder.key(it.name) },
            groups = ShoppingListGenerator.groupForStore(open, store?.aisleOrder ?: Aisle.defaultOrder),
            checked = inList.filter { it.checked }.sortedByDescending { it.checkedAt },
            suggestions = suggestions,
            shoppers = others.map { p -> Shopper(p.memberId, people[p.memberId], b.lists.firstOrNull { it.id == p.listId }?.name) },
            iAmShopping = iAmShopping,
            canManage = s.session.role == Role.Parent,
            people = people,
            usePantry = b.settings.usePantry,
            shoppersRow = shoppersRow(others, people, b.lists, list?.id),
            weekMissing = weekMissing(list, inList, e.weekPlan, today, e.handledMeals),
            showMergeHint = s.session.demo && e.mergeHintUnseen && inList.isNotEmpty() && !iAmShopping,
        )
    }

    private fun activeList(lists: List<ShoppingList>, selected: String): ShoppingList? =
        lists.firstOrNull { it.id == selected } ?: lists.firstOrNull { it.kind == ListKind.Grocery } ?: lists.firstOrNull()

    private suspend fun reloadHistory() {
        val since = clock.now() - StapleSuggester.LOOKBACK_DAYS.days
        try {
            history.value = shopping.purchaseHistory(since.toEpochMilliseconds()).map {
                PurchaseRecord(it.displayName, Instant.fromEpochMilliseconds(it.boughtAt))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Only the staple suggestions depend on it; they wait for the next tick.
            log.w(e) { "Purchase history could not be read" }
        }
    }

    /** The list to add to; creates "Veckohandling" when the family has no list yet. */
    private suspend fun listIdForAdding(): String = state.value.list?.id ?: adder.activeListId()

    private fun launchSafely(block: suspend () -> Unit) {
        launchJob(block)
    }

    private fun launchJob(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: IllegalStateException) {
            // A preview replaced by a newer one is cancelled; that is no failure (CancellationException is an
            // IllegalStateException on the JVM).
            if (e is CancellationException) throw e
            // E.g. no family selected yet; never crash the list, tell the user instead.
            log.w { "Shopping action failed: ${e::class.simpleName}" }
            messageChannel.send(ShoppingMessage.Failed)
        }
    }

    fun selectList(id: String) = launchSafely { prefs.set(Prefs.ActiveShoppingList, id) }

    fun add(text: String) = launchSafely {
        if (text.isBlank()) return@launchSafely
        report(adder.addText(listIdForAdding(), text))
    }

    private suspend fun report(outcome: AddOutcome) {
        when {
            !outcome.isEmpty -> messageChannel.send(ShoppingMessage.Added(outcome))
            outcome.names.isNotEmpty() -> messageChannel.send(ShoppingMessage.AlreadyOnList(outcome.names))
        }
    }

    fun undoAdd(outcome: AddOutcome) = launchSafely { adder.undo(outcome) }

    /**
     * Ticks [item] off (or puts it back). On the planning list ticking off always offers "Ångra". In store mode only the
     * first tick of the visit does, and the last item celebrates ("Allt i vagnen!") instead.
     */
    fun setChecked(item: ShoppingItem, checked: Boolean, inStore: Boolean = false) = launchSafely {
        val wasLast = checked && state.value.openCount == 1 && state.value.groups.any { g -> g.items.any { it.id == item.id } }
        shopping.setChecked(item, checked)
        when {
            !checked -> Unit
            wasLast && inStore -> messageChannel.send(ShoppingMessage.AllDone)
            !inStore -> messageChannel.send(ShoppingMessage.InCart(item, inStore = false))
            !undoOfferedThisVisit -> {
                undoOfferedThisVisit = true
                messageChannel.send(ShoppingMessage.InCart(item, inStore = true))
            }
        }
    }

    fun remove(item: ShoppingItem) = launchSafely {
        shopping.deleteItem(item.id)
        messageChannel.send(ShoppingMessage.Removed(item))
    }

    /** Undo for remove and for ticking off: puts the item back exactly as it was. */
    fun restore(item: ShoppingItem) = launchSafely { shopping.saveItem(item) }

    fun save(item: ShoppingItem) = launchSafely {
        val before = state.value.let { s -> (s.groups.flatMap { it.items } + s.checked).firstOrNull { it.id == item.id } }
        // "Lär sig av användarens korrigeringar": a corrected aisle is remembered for the whole family.
        if (before != null && before.aisle != item.aisle) shopping.rememberAisle(item.name, item.aisle)
        shopping.saveItem(item.copy(name = item.name.trim()))
    }

    /** "Rensa avbockade": removes the ticked-off items and remembers them for "Ångra" ([restoreCleared]). */
    fun clearChecked() = launchSafely {
        val list = state.value.list ?: return@launchSafely
        val cleared = removeChecked(list.id)
        if (cleared.isNotEmpty()) messageChannel.send(ShoppingMessage.Cleared(cleared))
    }

    /** Deletes what is ticked off on [listId]; the meals it was bought for no longer count as missing this week. */
    private suspend fun removeChecked(listId: String): List<ShoppingItem> {
        val cleared = shopping.getItems(listId).filter { it.checked }
        cleared.forEach { shopping.deleteItem(it.id) }
        markHandled(mealKeys(cleared))
        return cleared
    }

    /** Undo for "Rensa avbockade": the cleared items come back ticked off, as they were. */
    fun restoreCleared(items: List<ShoppingItem>) = launchSafely { shopping.saveItems(items) }

    fun addSuggestion(s: StapleSuggestion) = launchSafely {
        report(adder.addItems(listIdForAdding(), listOf(ParsedItem(s.name))))
    }

    /** "Inte nu": hides the shown suggestions until each item is bought again. */
    fun dismissSuggestions() = launchSafely {
        val now = clock.now().toEpochMilliseconds()
        val keys = state.value.suggestions.map { it.key }
        prefs.update(Prefs.DismissedStaples) { current -> current + keys.associateWith { now } }
    }

    /** × on one suggestion chip: hides [suggestion] until it is bought again, like [dismissSuggestions]. */
    fun dismissSuggestion(suggestion: StapleSuggestion) = launchSafely {
        val now = clock.now().toEpochMilliseconds()
        prefs.update(Prefs.DismissedStaples) { current -> current + (suggestion.key to now) }
    }

    /** "Jag förstår" on "Tips: mjölk två gånger blir en rad": the hint never comes back on this phone. */
    fun dismissMergeHint() = launchSafely { hints.dismiss(HintIds.SHOPPING_MERGE) }

    /** "Inte nu" on "Tacos saknas på listan": the card stays away until other recipes are planned. */
    fun dismissWeekMissing() = launchSafely {
        markHandled(state.value.weekMissing?.meals.orEmpty())
    }

    private suspend fun markHandled(keys: Set<String>) {
        if (keys.isEmpty()) return
        val today = today()
        prefs.update(ShoppingPrefs.HandledMeals) { current -> pruneHandled(current + keys, today) }
    }

    fun createList(name: String, kind: ListKind) = launchSafely {
        if (name.isBlank()) return@launchSafely
        val lists = shopping.lists.first()
        val list = ShoppingList(ids.newId(), name.trim(), kind, sortIndex = (lists.maxOfOrNull { it.sortIndex } ?: -1) + 1)
        shopping.saveList(list)
        prefs.set(Prefs.ActiveShoppingList, list.id)
    }

    fun renameList(name: String) = launchSafely {
        val list = state.value.list ?: return@launchSafely
        if (name.isNotBlank()) shopping.saveList(list.copy(name = name.trim()))
    }

    fun deleteList() = launchSafely {
        val list = state.value.list ?: return@launchSafely
        shopping.deleteList(list.id)
        val next = shopping.lists.first().firstOrNull { it.id != list.id }
        prefs.set(Prefs.ActiveShoppingList, next?.id.orEmpty())
    }

    fun setStore(storeId: String?) = launchSafely {
        val list = state.value.list ?: return@launchSafely
        shopping.saveList(list.copy(storeId = storeId))
    }

    /**
     * "Jag handlar nu": the family sees it while store mode is open (max an hour, then it expires by itself). Starts a
     * new visit, so the first tick offers "Ångra" again.
     */
    fun startShopping() = launchSafely {
        val list = state.value.list ?: return@launchSafely
        undoOfferedThisVisit = false
        shopping.startShopping(list.id)
        startedShopping = true
    }

    fun stopShopping() {
        startedShopping = false
        appScope.launch { shopping.stopShopping() }
    }

    /**
     * Leaves store mode. With [clear] ("Klar – töm vagnen") the ticked-off items are removed and the list underneath shows
     * "6 varor rensade · Ångra" ([storeReceipts]); without it they stay ("Behåll avbockade"). Runs in [appScope] because
     * the screen closes right away.
     */
    fun finishShopping(clear: Boolean) {
        val listId = state.value.list?.id
        stopShopping()
        if (!clear || listId == null) return
        appScope.launch {
            try {
                receipts.post(removeChecked(listId))
            } catch (e: IllegalStateException) {
                log.w { "Emptying the cart failed: ${e::class.simpleName}" }
            }
        }
    }

    override fun onCleared() {
        if (startedShopping) stopShopping()
    }

    /** Result from the camera scanner (null = cancelled). */
    fun scanned(code: String?) = launchSafely {
        if (code.isNullOrBlank()) return@launchSafely
        val result = adder.addScanned(listIdForAdding(), code)
        if (result.added.isEmpty && result.added.names.isEmpty()) {
            messageChannel.send(ShoppingMessage.UnknownProduct(result.barcode))
        } else {
            report(result.added)
        }
    }

    fun addNamedProduct(barcode: String, name: String) = launchSafely {
        if (name.isBlank()) return@launchSafely
        report(adder.addNamed(listIdForAdding(), barcode, name))
    }

    /** Opens "Handla till veckan" with today's remaining days of the week (today to Sunday), all ingredients ticked. */
    fun previewMealPlan() = showPreview(remainingDays(today()), emptySet())

    /** The quick choices in the sheet: [weeksAhead] 0 = the rest of this week, 1 = all of next week. */
    fun previewMealPlan(weeksAhead: Int) {
        val today = today()
        val days = if (weeksAhead <= 0) remainingDays(today) else today.isoWeek.plusWeeks(weeksAhead).days.toSet()
        showPreview(days intersect dayWindow(today).toSet(), preview.value?.excluded.orEmpty())
    }

    /** Adds or removes one day (within today and the 13 days after it). Unticked ingredients stay unticked. */
    fun toggleMealPlanDay(day: LocalDate) {
        val current = preview.value ?: return
        if (day !in dayWindow(today())) return
        showPreview(if (day in current.days) current.days - day else current.days + day, current.excluded)
    }

    /** Ticks or unticks one ingredient ("Finns hemma?" → untick if it is at home). */
    fun setMealPlanIngredient(key: String, included: Boolean) {
        val current = preview.value ?: return
        showPreview(current.days, if (included) current.excluded - key else current.excluded + key)
    }

    private fun showPreview(days: Set<LocalDate>, excluded: Set<String>) {
        previewJob?.cancel()
        previewJob = launchJob {
            val today = today()
            val listId = listIdForAdding()
            val result = mealShopping.preview(listId, days, excluded)
            val removing = result.plan.remove.toSet()
            val removed = if (removing.isEmpty()) emptyList() else shopping.getItems(listId).filter { it.id in removing }
            val ask = result.lines.filter { it.askIfAtHome }.mapTo(HashSet()) { it.key }
            val keys = result.lines.mapTo(HashSet()) { it.key }

            fun choices(items: List<ShoppingItem>, change: Boolean) = items.mapNotNull { item ->
                val key = ShoppingNames.canonical(item.name)
                if (key !in keys) return@mapNotNull null
                IngredientChoice(key, item, change, included = key !in result.excluded, askIfAtHome = key in ask)
            }
            val choices = choices(result.all.add, change = false) + choices(result.all.update, change = true)
            preview.value = MealPlanPreview(
                days = days,
                plan = result.plan,
                removed = removed.sortedBy { SwedishOrder.key(it.name) },
                choices = choices,
                trimmed = result.plan.update.filter { ShoppingNames.canonical(it.name) !in keys },
                coveredAtHome = result.coveredAtHome,
                meals = result.meals.mapNotNullTo(LinkedHashSet()) { entry -> entry.recipeId?.let { mealKey(entry.date, it) } },
                window = dayWindow(today),
                today = today,
            )
        }
    }

    fun dismissMealPlanPreview() {
        previewJob?.cancel()
        preview.value = null
    }

    /**
     * "Uppdatera listan" in the sheet: writes the ticked ingredients for the chosen days. Every planned meal on those days
     * counts as handled afterwards, so "saknas på listan" does not come back for recipes that needed nothing from the
     * store (or whose ingredients were all unticked).
     */
    fun generateFromMealPlan() = launchSafely {
        val p = preview.value ?: return@launchSafely
        previewJob?.cancel()
        preview.value = null
        val result = mealShopping.generate(listIdForAdding(), p.days, p.excluded)
        markHandled(p.meals)
        messageChannel.send(ShoppingMessage.Generated(result))
    }

    /** Today to Sunday. */
    private fun remainingDays(today: LocalDate): Set<LocalDate> = today.isoWeek.days.filterTo(LinkedHashSet()) { it >= today }

    private fun dayWindow(today: LocalDate): List<LocalDate> = (0 until MEAL_PLAN_DAYS).map { today.plusDays(it) }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L

        /** "Handla till veckan" can pick days up to two weeks ahead. */
        const val MEAL_PLAN_DAYS = 14
    }
}

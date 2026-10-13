package se.pusslet.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.PurchaseRecord
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.ShoppingPlan
import se.pusslet.core.shopping.StapleSuggester
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.Session
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.AddOutcome
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Someone else in the family is in the store right now ("Sara är i affären"). */
data class Shopper(val memberId: String, val person: FamilyPerson?, val listName: String?)

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
) {
    val openCount: Int get() = groups.sumOf { it.items.size }
    val isEmpty: Boolean get() = openCount == 0 && checked.isEmpty()
}

/** Preview of "Handla till matsedeln" for one week. */
data class MealPlanPreview(val week: IsoWeek, val plan: ShoppingPlan)

/** One-off feedback the screen shows as a snackbar or dialog. */
sealed interface ShoppingMessage {
    data class Added(val outcome: AddOutcome) : ShoppingMessage

    data class AlreadyOnList(val names: List<String>) : ShoppingMessage

    data class Removed(val item: ShoppingItem) : ShoppingMessage

    /** Ticked off in store mode (offers "Ångra"). */
    data class InCart(val item: ShoppingItem) : ShoppingMessage

    /** The last item went into the cart. */
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
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
    /** Outlives this view model, so "slutat handla" is written even while the screen is closing. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val log = Logger.withTag("Shopping")
    private val messageChannel = Channel<ShoppingMessage>(Channel.BUFFERED)
    val messages: Flow<ShoppingMessage> = messageChannel.receiveAsFlow()

    private val history = MutableStateFlow<List<PurchaseRecord>>(emptyList())
    private var startedShopping = false
    private val preview = MutableStateFlow<MealPlanPreview?>(null)
    val mealPlanPreview: StateFlow<MealPlanPreview?> = preview

    /** Presence expires by itself ("until"), so re-evaluate every minute. */
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

    val state: StateFlow<ShoppingUiState> = combine(
        base,
        social,
        history,
        prefs.observe(Prefs.DismissedStaples),
    ) { b, s, h, dismissed -> build(b, s, h, dismissed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShoppingUiState())

    init {
        // The purchase log grows when something is ticked off; reload it then (it is local and small).
        viewModelScope.launch {
            shopping.items.map { all -> all.count { it.checked } }.distinctUntilChanged().collect { reloadHistory() }
        }
    }

    private fun build(b: Base, s: Social, h: List<PurchaseRecord>, dismissed: Map<String, Long>): ShoppingUiState {
        val list = activeList(b.lists, b.selected)
        val store = (list?.storeId ?: b.settings.defaultStoreId)?.let { id -> b.stores.firstOrNull { it.id == id } }
        val inList = b.items.filter { it.listId == list?.id }
        val open = inList.filter { !it.checked }
        val me = s.session.memberId
        val live = s.presence.filter { it.until > s.now }
        val people = s.people.associateBy { it.id }
        val suggestions = if (list?.kind == ListKind.Grocery) {
            StapleSuggester.suggest(
                history = h,
                onList = open.map { it.name },
                today = s.now.toLocalDateTime(zone).date,
                zone = zone,
                dismissed = dismissed.mapValues { Instant.fromEpochMilliseconds(it.value) },
            )
        } else {
            emptyList()
        }
        return ShoppingUiState(
            loading = false,
            lists = b.lists,
            list = list,
            store = store,
            stores = b.stores.sortedBy { it.name.lowercase() },
            groups = ShoppingListGenerator.groupForStore(open, store?.aisleOrder ?: Aisle.defaultOrder),
            checked = inList.filter { it.checked }.sortedByDescending { it.checkedAt },
            suggestions = suggestions,
            shoppers = live.filter { it.memberId != me }.map { p ->
                Shopper(p.memberId, people[p.memberId], b.lists.firstOrNull { it.id == p.listId }?.name)
            },
            iAmShopping = live.any { it.memberId == me },
            canManage = s.session.role == Role.Parent,
            people = people,
            usePantry = b.settings.usePantry,
        )
    }

    private fun activeList(lists: List<ShoppingList>, selected: String): ShoppingList? =
        lists.firstOrNull { it.id == selected } ?: lists.firstOrNull { it.kind == ListKind.Grocery } ?: lists.firstOrNull()

    private suspend fun reloadHistory() {
        val since = clock.now() - StapleSuggester.LOOKBACK_DAYS.days
        history.value = shopping.purchaseHistory(since.toEpochMilliseconds()).map {
            PurchaseRecord(it.displayName, Instant.fromEpochMilliseconds(it.boughtAt))
        }
    }

    /** The list to add to; creates "Veckohandling" when the family has no list yet. */
    private suspend fun listIdForAdding(): String {
        state.value.list?.let { return it.id }
        activeList(shopping.lists.first(), prefs.get(Prefs.ActiveShoppingList))?.let { return it.id }
        val list = ShoppingList(ids.newId(), DEFAULT_LIST_NAME)
        shopping.saveList(list)
        prefs.set(Prefs.ActiveShoppingList, list.id)
        return list.id
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: IllegalStateException) {
                // E.g. no family selected yet; never crash the list, tell the user instead.
                log.w { "Shopping action failed: ${e::class.simpleName}" }
                messageChannel.send(ShoppingMessage.Failed)
            }
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

    fun setChecked(item: ShoppingItem, checked: Boolean, inStore: Boolean = false) = launchSafely {
        val wasLast = checked && state.value.openCount == 1 && state.value.groups.any { g -> g.items.any { it.id == item.id } }
        shopping.setChecked(item, checked)
        when {
            wasLast -> messageChannel.send(ShoppingMessage.AllDone)
            checked && inStore -> messageChannel.send(ShoppingMessage.InCart(item))
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

    fun clearChecked() = launchSafely { state.value.list?.let { shopping.clearChecked(it.id) } }

    fun addSuggestion(s: StapleSuggestion) = launchSafely {
        report(adder.addItems(listIdForAdding(), listOf(ParsedItem(s.name))))
    }

    /** "Inte nu": hides the shown suggestions until each item is bought again. */
    fun dismissSuggestions() = launchSafely {
        val now = clock.now().toEpochMilliseconds()
        val keys = state.value.suggestions.map { it.key }
        prefs.update(Prefs.DismissedStaples) { current -> current + keys.associateWith { now } }
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

    /** "Jag handlar nu": the family sees it while store mode is open (max an hour, then it expires by itself). */
    fun startShopping() = launchSafely {
        val list = state.value.list ?: return@launchSafely
        shopping.startShopping(list.id)
        startedShopping = true
    }

    fun stopShopping() {
        startedShopping = false
        appScope.launch { shopping.stopShopping() }
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

    /** Shows what "Handla till matsedeln" would change; [weeksAhead] 0 = this week, 1 = next week. */
    fun previewMealPlan(weeksAhead: Int) = launchSafely {
        val today = clock.now().toLocalDateTime(zone).date
        val week = today.isoWeek.plusWeeks(weeksAhead)
        preview.value = MealPlanPreview(week, mealShopping.preview(listIdForAdding(), week))
    }

    fun dismissMealPlanPreview() {
        preview.value = null
    }

    fun generateFromMealPlan() = launchSafely {
        val p = preview.value ?: return@launchSafely
        preview.value = null
        val result = mealShopping.generate(listIdForAdding(), p.week)
        messageChannel.send(ShoppingMessage.Generated(result))
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_LIST_NAME = "Veckohandling"
    }
}

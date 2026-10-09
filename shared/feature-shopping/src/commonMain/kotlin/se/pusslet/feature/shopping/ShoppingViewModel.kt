package se.pusslet.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.ShoppingPresence
import se.pusslet.core.model.Store
import se.pusslet.core.shopping.AisleClassifier
import se.pusslet.core.shopping.AisleGroup
import se.pusslet.core.shopping.Purchase
import se.pusslet.core.shopping.ShoppingListGenerator
import se.pusslet.core.shopping.StapleSuggester
import se.pusslet.core.shopping.StapleSuggestion
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.usecase.ShoppingQuickAdd
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Someone in the family who is in a shop right now ("Sara handlar nu"). */
data class ShopperUi(val person: FamilyPerson, val listId: String, val isMe: Boolean)

data class ShoppingUiState(
    val loading: Boolean = true,
    val lists: List<ShoppingList> = emptyList(),
    val list: ShoppingList? = null,
    val store: Store? = null,
    val stores: List<Store> = emptyList(),
    /** Unchecked items in the store's walking order. */
    val groups: List<AisleGroup> = emptyList(),
    /** In the trolley, most recently checked first. */
    val checked: List<ShoppingItem> = emptyList(),
    val staples: List<StapleSuggestion> = emptyList(),
    val shoppers: List<ShopperUi> = emptyList(),
    val people: Map<String, FamilyPerson> = emptyMap(),
    val canManage: Boolean = true,
    /** Sum of the family's own prices for what is left (null when no prices are entered). */
    val total: Double? = null,
    /** A barcode Open Food Facts did not know: the user names the item. */
    val unknownBarcode: String? = null,
    val scanning: Boolean = false,
) {
    val remaining: Int get() = groups.sumOf { it.items.size }
    val iAmShopping: Boolean get() = shoppers.any { it.isMe && it.listId == list?.id }
}

sealed interface ShoppingMessage {
    data class Deleted(val item: ShoppingItem) : ShoppingMessage

    data class Checked(val item: ShoppingItem) : ShoppingMessage

    data class Scanned(val name: String) : ShoppingMessage

    /** Someone else added to the list while I am in the shop. */
    data class AddedByOther(val person: FamilyPerson, val name: String) : ShoppingMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val products: ProductLookup,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** How often presence and staples are re-evaluated; 0 = only when data changes (tests). */
    tickMillis: Long = TICK_MS,
) : ViewModel() {
    private val selectedId = MutableStateFlow<String?>(null)
    private val unknownBarcode = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)
    private val quickAdd = ShoppingQuickAdd(shopping, prefs, sessions, ids, clock)
    private val messageChannel = Channel<ShoppingMessage>(Channel.BUFFERED)
    val messages: Flow<ShoppingMessage> = messageChannel.receiveAsFlow()
    private val me get() = sessions.session.value.memberId

    /** Ticks so presence expires and staples become due without user interaction. */
    private val ticks: Flow<Unit> = if (tickMillis <= 0) {
        flowOf(Unit)
    } else {
        flow {
            while (true) {
                emit(Unit)
                delay(tickMillis)
            }
        }
    }

    private data class Data(
        val lists: List<ShoppingList>,
        val items: List<ShoppingItem>,
        val stores: List<Store>,
        val presence: List<ShoppingPresence>,
        val settings: FamilySettings,
    )

    private val data: Flow<Data> =
        combine(shopping.lists, shopping.items, shopping.stores, shopping.presence, family.settings) { l, i, s, p, st ->
            Data(l, i, s, p, st)
        }

    private val staples: Flow<List<StapleSuggestion>> = combine(shopping.items, prefs.observe(Prefs.DismissedStaples), ticks) {
        items,
        dismissed,
        _,
        ->
        items to dismissed
    }.mapLatest { (items, dismissed) ->
        val t = clock.now()
        val history = shopping.purchaseHistory((t - HISTORY).toEpochMilliseconds())
            .map { Purchase(it.displayName, Instant.fromEpochMilliseconds(it.boughtAt)) }
        val active = dismissed.filterValues { t.toEpochMilliseconds() - it < DISMISS_FOR.inWholeMilliseconds }.keys
        val onList = items.filter { !it.checked }.map { it.name }.toSet()
        StapleSuggester.suggest(history, t, onList = onList, dismissed = active)
    }

    private val ui = combine(selectedId, unknownBarcode, scanning, staples) { s, u, sc, st -> UiExtra(s, u, sc, st) }

    private data class UiExtra(val selected: String?, val unknown: String?, val scanning: Boolean, val staples: List<StapleSuggestion>)

    val state: StateFlow<ShoppingUiState> = combine(data, family.people, ui, ticks) { d, people, u, _ ->
        build(d, people, u, clock.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShoppingUiState())

    private fun build(d: Data, people: List<FamilyPerson>, u: UiExtra, t: Instant): ShoppingUiState {
        val list = d.lists.firstOrNull { it.id == u.selected } ?: d.lists.firstOrNull()
        val store = d.stores.firstOrNull { it.id == list?.storeId } ?: d.stores.firstOrNull { it.id == d.settings.defaultStoreId }
        val items = d.items.filter { it.listId == list?.id }
        val byId = people.associateBy { it.id }
        val open = items.filter { !it.checked }
        return ShoppingUiState(
            loading = false,
            lists = d.lists,
            list = list,
            store = store,
            stores = d.stores,
            groups = ShoppingListGenerator.groupForStore(items, store?.aisleOrder ?: Aisle.defaultOrder),
            checked = items.filter { it.checked }.sortedByDescending { it.checkedAt },
            staples = if (list?.kind == ListKind.Grocery) u.staples else emptyList(),
            shoppers = d.presence.filter { it.until > t }.mapNotNull { p ->
                byId[p.memberId]?.let { ShopperUi(it, p.listId, isMe = p.memberId == me) }
            },
            people = byId,
            canManage = sessions.session.value.role != Role.Caregiver,
            total = open.mapNotNull { it.price }.takeIf { it.isNotEmpty() }?.sum(),
            unknownBarcode = u.unknown,
            scanning = u.scanning,
        )
    }

    fun selectList(id: String) {
        selectedId.value = id
        launch { prefs.set(Prefs.ActiveShoppingList, id) }
    }

    init {
        // Follows the active list, also when it is chosen elsewhere (a search hit, the widget).
        viewModelScope.launch {
            prefs.observe(Prefs.ActiveShoppingList).collect { if (it.isNotEmpty()) selectedId.value = it }
        }
    }

    /** "mjölk, 2 gurka och pasta" -> three items, each in its aisle (the family's own corrections win). */
    fun addText(text: String) = withList { list -> quickAdd.add(list.id, text) }

    fun setChecked(item: ShoppingItem, checked: Boolean) = launch {
        shopping.setChecked(item, checked)
        if (checked) messageChannel.send(ShoppingMessage.Checked(item))
    }

    /** Edits from the item sheet; a changed aisle is remembered for the whole family. */
    fun saveItem(item: ShoppingItem) = launch {
        val before = shopping.getItems(item.listId).firstOrNull { it.id == item.id }
        shopping.saveItem(item.copy(name = item.name.trim().ifEmpty { before?.name.orEmpty() }))
        if (before != null && before.aisle != item.aisle) shopping.rememberAisle(item.name, item.aisle)
    }

    fun delete(item: ShoppingItem) = launch {
        shopping.deleteItem(item.id)
        messageChannel.send(ShoppingMessage.Deleted(item))
    }

    /** Undo for delete and check-off. */
    fun restore(item: ShoppingItem) = launch { shopping.saveItem(item) }

    fun clearChecked() = withList { shopping.clearChecked(it.id) }

    fun addStaple(s: StapleSuggestion) = addText(s.name)

    fun dismissStaple(s: StapleSuggestion) = launch {
        prefs.update(Prefs.DismissedStaples) { it + (s.key to clock.now().toEpochMilliseconds()) }
    }

    fun startShopping() = withList { shopping.startShopping(it.id, SHOPPING_MINUTES) }

    fun stopShopping() = launch { shopping.stopShopping() }

    /** Store mode runs this while it is on screen: keeps the "Sara handlar nu" sign fresh. */
    suspend fun keepShopping() {
        while (true) {
            val list = currentList() ?: return
            shopping.startShopping(list.id, SHOPPING_MINUTES)
            delay(PRESENCE_REFRESH_MS)
        }
    }

    /** Store mode runs this while it is on screen: announces items others add while I am in the shop. */
    suspend fun watchForAdditions() {
        val seen = shopping.items.first().map { it.id }.toMutableSet()
        shopping.items.collect { items ->
            val list = currentList() ?: return@collect
            items.filter { it.id !in seen && it.listId == list.id && it.addedBy != null && it.addedBy != me }.forEach { item ->
                family.people.first().firstOrNull {
                    it.id == item.addedBy
                }?.let { messageChannel.send(ShoppingMessage.AddedByOther(it, item.name)) }
            }
            seen += items.map { it.id }
        }
    }

    /** Barcode from the scanner -> Open Food Facts -> an item; unknown codes ask for a name. */
    fun onBarcode(code: String?) = withList { list ->
        if (code.isNullOrBlank()) return@withList
        scanning.value = true
        val product = try {
            products.product(code)
        } finally {
            scanning.value = false
        }
        val name = product?.name?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null) {
            unknownBarcode.value = code
            return@withList
        }
        val overrides = shopping.aisleOverrides.first()
        val aisle = AisleClassifier.classify(name, overrides).takeIf { it != Aisle.Other }
            ?: product.categories.map { AisleClassifier.classify(it, overrides) }.firstOrNull { it != Aisle.Other }
            ?: Aisle.Other
        shopping.saveItem(
            ShoppingItem(
                id = ids.newId(),
                listId = list.id,
                name = name,
                note = listOfNotNull(product.brand, product.quantity).joinToString(" · "),
                aisle = aisle,
                barcode = code,
                addedBy = me,
                addedAt = clock.now(),
            ),
        )
        messageChannel.send(ShoppingMessage.Scanned(name))
    }

    fun nameUnknownBarcode(name: String) = withList { list ->
        val code = unknownBarcode.value
        unknownBarcode.value = null
        if (name.isBlank()) return@withList
        val overrides = shopping.aisleOverrides.first()
        shopping.saveItem(
            ShoppingItem(
                id = ids.newId(),
                listId = list.id,
                name = name.trim().replaceFirstChar { it.uppercase() },
                aisle = AisleClassifier.classify(name, overrides),
                barcode = code,
                addedBy = me,
                addedAt = clock.now(),
            ),
        )
    }

    fun dismissUnknownBarcode() {
        unknownBarcode.value = null
    }

    fun createList(name: String, kind: ListKind) = launch {
        val lists = shopping.lists.first()
        val list = ShoppingList(ids.newId(), name.trim(), kind, sortIndex = (lists.maxOfOrNull { it.sortIndex } ?: -1) + 1)
        shopping.saveList(list)
        selectList(list.id)
    }

    fun updateList(list: ShoppingList) = launch { shopping.saveList(list.copy(name = list.name.trim())) }

    fun deleteList(id: String) = launch {
        shopping.deleteList(id)
        if (selectedId.value == id) selectedId.value = null
    }

    private suspend fun currentList(): ShoppingList? {
        val lists = shopping.lists.first()
        return lists.firstOrNull { it.id == selectedId.value } ?: lists.firstOrNull()
    }

    private fun withList(block: suspend (ShoppingList) -> Unit) = launch { currentList()?.let { block(it) } }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 60_000L
        const val SHOPPING_MINUTES = 45
        val PRESENCE_REFRESH_MS = 10.minutes.inWholeMilliseconds
        val HISTORY = 120.days
        val DISMISS_FOR = 3.days
    }
}

package se.pusslet.feature.shopping

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A family list of 600 items (BRIEF fas 4: "listor med 500+ varor") through the real pipeline: documents decoded from JSON,
 * the shopping list grouped by aisle, staple suggestions from a year of purchases, ticking off and adding. The budgets
 * are wide (a slow CI machine passes), so a failure means something grew worse than linear, not a slow day. Times are
 * printed for the record (docs/PROGRESS.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListPerformanceTest {
    private class Prefs0 : PreferenceDao {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observe(key: String): Flow<String?> = values.map { it[key] }

        override suspend fun get(key: String): String? = values.value[key]

        override suspend fun put(entity: PreferenceEntity) {
            values.value = values.value + (entity.key to entity.value)
        }

        override suspend fun remove(key: String) {
            values.value = values.value - key
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }

    private class Purchases : PurchaseLogDao {
        val log = mutableListOf<PurchaseLogEntity>()

        override suspend fun insert(entity: PurchaseLogEntity) {
            log += entity
        }

        override suspend fun since(familyId: String, since: Long) = log.filter { it.familyId == familyId && it.boughtAt >= since }

        override suspend fun deleteFamily(familyId: String) = log.clear()
    }

    private object NoProducts : ProductLookup {
        override suspend fun product(barcode: String): ProductInfo? = null
    }

    private val now = Instant.parse("2026-10-13T16:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }
    private val zone = TimeZone.of("Europe/Stockholm")
    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    private val ids = SequentialIdGenerator()
    private val purchases = Purchases()
    private val prefs = PreferencesRepository(Prefs0())
    private val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    private val shopping = ShoppingRepository(docs, purchases, sessions, clock)
    private val meals = MealRepository(docs)
    private val adder = ShoppingAdder(shopping, NoProducts, prefs, sessions, ids, clock)
    private val mealShopping = MealShopping(meals, RecipeRepository(docs), shopping, family, sessions, ids, clock)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun timed(block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow()
    }

    @Test
    fun sixHundredItemsStayQuick() = runTest {
        family.saveMember(Member("m-sara", "Sara"))
        shopping.saveList(ShoppingList("l-vecka", "Veckohandling", sortIndex = 0))
        prefs.set(Prefs.ActiveShoppingList, "l-vecka")
        val aisles = Aisle.entries
        repeat(ITEMS) { i ->
            shopping.saveItem(ShoppingItem("i$i", "l-vecka", "vara nummer $i", aisle = aisles[i % aisles.size]))
        }
        // A year of weekly shopping in the purchase log, for the staple suggestions.
        repeat(PURCHASES) { i ->
            purchases.log += PurchaseLogEntity(
                familyId = "f",
                normalizedName = "vara nummer ${i % 80}",
                displayName = "vara nummer ${i % 80}",
                boughtAt = (now - (i % 365).days).toEpochMilliseconds(),
            )
        }

        val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        lateinit var vm: ShoppingViewModel
        val open = timed {
            vm = ShoppingViewModel(
                shopping,
                adder,
                mealShopping,
                meals,
                family,
                prefs,
                sessions,
                ids,
                clock,
                zone,
                eager,
                ShoppingReceipts(),
                FirstTimeHints(prefs),
                // On the test's own thread, so the times are the whole work of each step.
                compute = UnconfinedTestDispatcher(testScheduler),
            )
            eager.launch { vm.state.collect {} }
        }
        assertEquals(ITEMS, vm.state.value.groups.sumOf { it.items.size })

        val items = vm.state.value.groups.flatMap { it.items }.take(TICKS)
        val ticks = timed { items.forEach { vm.setChecked(it, checked = true, inStore = true) } }
        assertEquals(ITEMS - TICKS, vm.state.value.groups.sumOf { it.items.size })

        val add = timed { vm.add((1..ADDS).joinToString(", ") { "ny vara $it" }) }
        assertEquals(ITEMS - TICKS + ADDS, vm.state.value.groups.sumOf { it.items.size })

        println("Shopping list with $ITEMS items: open $open, $TICKS ticks ${ticks / TICKS} each, add $ADDS at once $add")
        assertTrue(open < OPEN_BUDGET, "Opening took $open")
        assertTrue(ticks / TICKS < TICK_BUDGET, "A tick took ${ticks / TICKS}")
        assertTrue(add < ADD_BUDGET, "Adding took $add")
    }

    private companion object {
        const val ITEMS = 600
        const val PURCHASES = 1_000
        const val TICKS = 50
        const val ADDS = 30
        val OPEN_BUDGET = 3_000.milliseconds
        val TICK_BUDGET = 100.milliseconds
        val ADD_BUDGET = 2_000.milliseconds
    }
}

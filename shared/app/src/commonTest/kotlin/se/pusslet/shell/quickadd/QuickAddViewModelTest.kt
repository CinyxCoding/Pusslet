package se.pusslet.shell.quickadd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.SwedishQuickAddParser
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.db.CacheDao
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.db.CacheSummary
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.SickDayRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.HandoffAssigner
import se.pusslet.data.usecase.QuickAddExecutor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Quick add learns (UX plan 4.6): chips with the family's names until 3 saves, one sentence per action with "Ändra",
 * parsing while typing and "Fyll i själv". Real repositories over the in-memory store; "today" is Monday 2026-10-12, 10:00.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {
    private class MemoryPreferences : PreferenceDao {
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

    private class MemoryCache : CacheDao {
        private val entries = MutableStateFlow<Map<String, CacheEntryEntity>>(emptyMap())

        override suspend fun get(key: String) = entries.value[key]

        override fun observe(key: String): Flow<CacheEntryEntity?> = entries.map { it[key] }

        override suspend fun put(entity: CacheEntryEntity) {
            entries.value = entries.value + (entity.key to entity)
        }

        override suspend fun setError(key: String, error: String?) = Unit

        override suspend fun deletePrefix(prefix: String) {
            entries.value = entries.value.filterKeys { !it.startsWith(prefix) }
        }

        override suspend fun summaries() = entries.value.values.map { CacheSummary(it.key, it.fetchedAt, it.lastError) }
    }

    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    /** One phone: [prefs] survive a "restart" when passed to a new world. */
    private class World(val prefs: PreferencesRepository = PreferencesRepository(MemoryPreferences())) {
        val clock = object : Clock {
            override fun now() = Instant.parse("2026-10-12T08:00:00Z")
        }
        val zone = TimeZone.of("Europe/Stockholm")
        val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-alva", uid = "u", role = Role.Parent))
        val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
        val ids = SequentialIdGenerator()
        val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
        val calendar = CalendarRepository(docs, MemoryCache(), prefs, NoDeviceCalendars, sessions)
        val executor = QuickAddExecutor(
            calendar = calendar,
            tasks = TaskRepository(docs),
            shopping = ShoppingRepository(docs, NoPurchases, sessions, clock),
            meals = MealRepository(docs),
            recipes = RecipeRepository(docs),
            family = family,
            handoffs = HandoffAssigner(calendar),
            sickDays = SickDayRepository(docs, sessions),
            prefs = prefs,
            sessions = sessions,
            ids = ids,
            zone = zone,
            clock = clock,
        )

        suspend fun adults(vararg names: String) = names.forEach { family.saveMember(Member("m-${it.lowercase()}", it)) }

        suspend fun children(vararg names: String) = names.forEach { family.saveChild(Child("c-${it.lowercase()}", it)) }

        fun viewModel() = QuickAddViewModel(SwedishQuickAddParser(), executor, family, prefs, sessions, clock, zone)
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.saveText(vm: QuickAddViewModel, text: String) {
        vm.start(text)
        var saved = false
        vm.save { saved = true }
        advanceUntilIdle()
        assertTrue(saved, "\"$text\" was not saved")
    }

    @Test
    fun theChipsUseTheFamilysOwnChildren() = runTest {
        val world = World()
        world.adults("Alva", "Omar")
        world.children("Noah", "Maja")
        val vm = world.viewModel()
        advanceUntilIdle()

        // The family lists children oldest first, by name when no birth year is set: Maja before Noah.
        val state = vm.state.value
        assertEquals(
            listOf(
                QuickAddExample(QuickAddExampleKind.Event, "Maja"),
                QuickAddExample(QuickAddExampleKind.Shopping),
                QuickAddExample(QuickAddExampleKind.Handoff, "Noah"),
            ),
            state.examples,
        )
        assertEquals("Maja", state.hintName)
    }

    @Test
    fun aFamilyWithoutChildrenGetsTheGenericHint() = runTest {
        val world = World()
        world.adults("Alva")
        val vm = world.viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.hintName)
        assertTrue(vm.state.value.examples.all { it.name == null })
        assertEquals(QuickAddExampleKind.Meal, vm.state.value.examples.last().kind)
    }

    @Test
    fun theChipsGoAwayAfterThreeSavesAndStayAwayAfterARestart() = runTest {
        val world = World()
        world.adults("Alva")
        world.children("Noah")
        val vm = world.viewModel()
        advanceUntilIdle()

        saveText(vm, "Mjölk")
        saveText(vm, "Mjölk")
        assertEquals(3, vm.state.value.examples.size, "two saves are not enough")
        saveText(vm, "tacos fre")
        assertTrue(vm.state.value.examples.isEmpty())
        assertEquals(3, world.prefs.get(Prefs.QuickAddSaves))

        val restarted = World(world.prefs).also { it.children("Noah") }.viewModel()
        advanceUntilIdle()
        assertTrue(restarted.state.value.examples.isEmpty(), "the count is kept on the phone")
        assertEquals("Noah", restarted.state.value.hintName, "the placeholder still uses the child's name")
    }

    @Test
    fun aPhoneThatAlreadyUsedQuickAddSkipsTheChips() = runTest {
        val world = World()
        world.prefs.set(Prefs.RecentQuickAdds, listOf("mjölk", "tacos fre", "simskola lör 10"))
        world.children("Noah")
        val vm = world.viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.examples.isEmpty())
    }

    @Test
    fun itParsesWhileTypingAndCountsWhatWillBeSaved() = runTest {
        val world = World()
        world.children("Noah")
        val vm = world.viewModel()
        advanceUntilIdle()

        vm.onTextChange("Noah kalas lör 14-16")
        assertEquals("Noah kalas lör 14-16", vm.state.value.text, "the field shows the text at once")
        advanceTimeBy(500)
        assertEquals(1, vm.state.value.actions.size)

        vm.onTextChange("Noah kalas lör 14-16, köp present")
        advanceTimeBy(500)
        val actions = vm.state.value.actions
        assertEquals(2, actions.size, "Spara 2 saker")
        assertIs<QuickAddAction.CreateEvent>(actions[0])
        assertIs<QuickAddAction.CreateTask>(actions[1])
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun andraOpensOneActionAndAnEditClearsItsMark() = runTest {
        val world = World()
        world.children("Noah")
        val vm = world.viewModel()
        advanceUntilIdle()

        vm.start("Noah dans tis 17")
        assertEquals(setOf(UncertainField.Time), vm.state.value.uncertain)
        assertTrue(vm.state.value.editing.isEmpty(), "the preview starts as a sentence")

        vm.toggleEditing(0)
        assertEquals(setOf(0), vm.state.value.editing)
        vm.setStart(0, LocalTime(17, 30))
        assertTrue(vm.state.value.uncertain.isEmpty(), "the user has checked the time")

        vm.toggleEditing(0)
        assertTrue(vm.state.value.editing.isEmpty(), "Klar closes the fields again")
        vm.toggleEditing(5)
        assertTrue(vm.state.value.editing.isEmpty(), "no action at that place")
    }

    @Test
    fun removingAnActionKeepsTheRightOneOpen() = runTest {
        val vm = World().viewModel()
        advanceUntilIdle()

        vm.start("kalas lör 14-16, köp present, mjölk")
        assertEquals(3, vm.state.value.actions.size)
        vm.toggleEditing(2)
        vm.removeAction(0)
        assertEquals(setOf(1), vm.state.value.editing)
        vm.removeAction(1)
        assertTrue(vm.state.value.editing.isEmpty())
    }

    @Test
    fun typingKeepsAnOpenActionOpenWhileItIsThere() = runTest {
        val vm = World().viewModel()
        advanceUntilIdle()

        vm.start("kalas lör 14-16, köp present")
        vm.toggleEditing(1)
        vm.onTextChange("kalas lör 14-17, köp present")
        advanceTimeBy(500)
        assertEquals(setOf(1), vm.state.value.editing)
        vm.onTextChange("kalas lör 14-17")
        advanceTimeBy(500)
        assertTrue(vm.state.value.editing.isEmpty())
    }

    @Test
    fun fillInHandsTheEditorWhatWasTyped() = runTest {
        val world = World()
        world.children("Noah")
        val vm = world.viewModel()
        advanceUntilIdle()

        vm.start("Noah dans tis 17:00")
        val p = vm.prefill()
        assertEquals("Dans", p.title)
        assertEquals(LocalDate(2026, 10, 13), p.date)
        assertEquals(LocalTime(17, 0), p.startTime)
        assertEquals(LocalTime(18, 0), p.endTime)
        assertEquals(listOf("c-noah"), p.participantIds)
        assertFalse(p.allDay)
    }

    @Test
    fun fillInDoesNotWaitForTheDebounce() = runTest {
        val vm = World().viewModel()
        advanceUntilIdle()

        vm.onTextChange("Frisör tor 9:30")
        val p = vm.prefill()
        assertEquals("Frisör", p.title)
        assertEquals(LocalDate(2026, 10, 15), p.date)
        assertEquals(LocalTime(9, 30), p.startTime)
    }

    @Test
    fun fillInUsesTheDayTheSheetWasOpenedFor() = runTest {
        val vm = World().viewModel()
        advanceUntilIdle()

        vm.start(date = LocalDate(2026, 10, 17))
        vm.onTextChange("ring farmor")
        val p = vm.prefill()
        assertEquals("Ring farmor", p.title)
        assertEquals(LocalDate(2026, 10, 17), p.date)
    }
}

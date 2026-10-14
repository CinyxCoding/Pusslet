package se.pusslet.data.usecase

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Role
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.QuickAddResult
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The confirmation after quick add says what happened and where, and offers Ångra only when it really restores. */
class QuickAddExecutorTest {
    private object FixedClock : Clock {
        // Tuesday 13 October 2026, 18:00 in Stockholm.
        override fun now() = Instant.parse("2026-10-13T16:00:00Z")
    }

    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long) = emptyList<PurchaseLogEntity>()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    private val zone = TimeZone.of("Europe/Stockholm")
    private val sessions = FixedSessionProvider(Session(familyId = "f", memberId = "m-sara", uid = "u", role = Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, FixedClock)
    private val ids = SequentialIdGenerator()
    private val prefs = PreferencesRepository(FakePreferenceDao())
    private val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
    private val executor = QuickAddExecutor(
        calendar = calendar,
        tasks = TaskRepository(docs),
        shopping = ShoppingRepository(docs, NoPurchases, sessions, FixedClock),
        meals = MealRepository(docs),
        recipes = RecipeRepository(docs),
        family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, FixedClock),
        handoffs = HandoffAssigner(calendar),
        prefs = prefs,
        sessions = sessions,
        ids = ids,
        zone = zone,
        clock = FixedClock,
    )
    private val thursday = LocalDate(2026, 10, 15)
    private val saturday = LocalDate(2026, 10, 17)

    private suspend fun run(vararg actions: QuickAddAction): QuickAddOutcome = executor.execute(QuickAddResult("", actions.toList()))

    @Test
    fun onlyShoppingItemsOfferShowInsteadOfUndo() = runTest {
        val outcome = run(QuickAddAction.AddShoppingItems(listOf(ParsedItem("mjölk"), ParsedItem("bröd"))))
        assertEquals(QuickAddReceipt.Items(2), outcome.receipt)
        assertFalse(outcome.receipt.undo)
    }

    @Test
    fun anEventSaysWhereItLanded() = runTest {
        val outcome = run(
            QuickAddAction.CreateEvent("Kalas", saturday, startTime = LocalTime(14, 0), endTime = LocalTime(16, 0)),
            QuickAddAction.CreateTask("Köp present", due = thursday, linkedToEvent = true),
        )
        assertEquals(QuickAddReceipt.InCalendar(SavedSlot(saturday, LocalTime(14, 0))), outcome.receipt)
        assertTrue(outcome.receipt.undo)
    }

    @Test
    fun mealsAndTasksHaveTheirOwnReceipts() = runTest {
        assertEquals(QuickAddReceipt.OnMenu(saturday), run(QuickAddAction.PlanMeal(saturday, "Tacos", MealSlot.Dinner)).receipt)
        assertEquals(QuickAddReceipt.TaskSaved, run(QuickAddAction.CreateTask("Ring tandläkaren")).receipt)
    }

    @Test
    fun changingAnExistingPickUpHasNoUndo() = runTest {
        calendar.saveEvent(
            Event(
                id = "school",
                title = "Förskola",
                start = LocalDateTime(thursday, LocalTime(8, 0)),
                end = LocalDateTime(thursday, LocalTime(16, 0)),
                participantIds = listOf("simon"),
                dropOff = Handoff(null),
                pickUp = Handoff(null),
            ),
        )
        val outcome = run(QuickAddAction.AssignHandoff("johan", HandoffKind.PickUp, thursday, childIds = listOf("simon")))

        val expected = AssignedHandoff(HandoffKind.PickUp, "johan", thursday, time = null, created = false)
        assertEquals(QuickAddReceipt.HandoffChanged(expected), outcome.receipt)
        assertFalse(outcome.receipt.undo)
        assertEquals("johan", calendar.event("school")?.pickUp?.personId)
    }

    @Test
    fun aDayWithoutPickUpGetsANewOneThatCanBeUndone() = runTest {
        val outcome = run(QuickAddAction.AssignHandoff("johan", HandoffKind.PickUp, saturday, childIds = listOf("simon")))

        val expected = AssignedHandoff(HandoffKind.PickUp, "johan", saturday, time = LocalTime(16, 0), created = true)
        assertEquals(QuickAddReceipt.HandoffCreated(expected), outcome.receipt)
        assertTrue(outcome.receipt.undo)

        val created = outcome.eventIds.single()
        executor.undo(outcome)
        assertNull(calendar.event(created))
    }

    @Test
    fun aRepeatingHandOffIsANewSeriesInTheCalendar() = runTest {
        val outcome = run(
            QuickAddAction.AssignHandoff(
                "johan",
                HandoffKind.DropOff,
                thursday,
                childIds = listOf("simon"),
                time = LocalTime(7, 50),
                recurringDays = setOf(thursday.dayOfWeek),
            ),
        )
        assertEquals(QuickAddReceipt.InCalendar(SavedSlot(thursday, LocalTime(7, 50))), outcome.receipt)
    }

    @Test
    fun mixedOrEmptyOutcomesFallBackToSaved() {
        assertEquals(QuickAddReceipt.Saved(undo = false), QuickAddOutcome().receipt)
        val mixed = QuickAddOutcome(taskIds = listOf("t"), itemIds = listOf("i"))
        assertEquals(QuickAddReceipt.Saved(undo = true), mixed.receipt)
    }
}

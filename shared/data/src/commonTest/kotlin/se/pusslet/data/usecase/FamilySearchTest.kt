package se.pusslet.data.usecase

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Contact
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.time.plusDays
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.db.PurchaseLogDao
import se.pusslet.data.db.PurchaseLogEntity
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** "Sök" (ADR-052). "Today" is Tuesday 13 October 2026. */
class FamilySearchTest {
    private val today = LocalDate(2026, 10, 13)
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-10-13T06:00:00Z")
    }
    private val zone = TimeZone.of("Europe/Stockholm")
    private val sessions = FixedSessionProvider(Session("f", "m-sara", "u", Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions, clock)
    private val ids = SequentialIdGenerator()
    private val calendar = CalendarRepository(docs, FakeCacheDao(), PreferencesRepository(FakePreferenceDao()), NoDeviceCalendars, sessions)
    private val recipes = RecipeRepository(docs)
    private val tasks = TaskRepository(docs)
    private val shopping = ShoppingRepository(docs, NoPurchases, sessions, clock)
    private val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    private val search = FamilySearch(calendar, recipes, tasks, shopping, family, clock, zone)

    private object NoPurchases : PurchaseLogDao {
        override suspend fun insert(entity: PurchaseLogEntity) = Unit

        override suspend fun since(familyId: String, since: Long): List<PurchaseLogEntity> = emptyList()

        override suspend fun deleteFamily(familyId: String) = Unit
    }

    private suspend fun seed() {
        calendar.saveEvent(
            Event(
                "swim",
                "Simskola",
                LocalDateTime(today.plusDays(-14), LocalTime(9, 0)),
                LocalDateTime(today.plusDays(-14), LocalTime(9, 45)),
                rrule = "FREQ=WEEKLY",
                location = "Fyrishov",
            ),
        )
        calendar.saveEvent(
            Event(
                "dentist",
                "Tandläkare",
                LocalDateTime(today.plusDays(5), LocalTime(14, 30)),
                LocalDateTime(today.plusDays(5), LocalTime(15, 0)),
            ),
        )
        recipes.save(Recipe("r1", "Fiskgratäng", ingredients = listOf(Ingredient("torskfilé"), Ingredient("potatis"))))
        recipes.save(Recipe("r2", "Pannkakor", ingredients = listOf(Ingredient("mjölk"), Ingredient("ägg"))))
        recipes.save(Recipe("r3", "Ostkaka"))
        recipes.save(Recipe("r4", "Östron på is"))
        tasks.save(FamilyTask("t1", "Köp simglasögon", note = "Till simskolan"))
        shopping.saveList(ShoppingList("l1", "Veckohandling"))
        shopping.saveItem(ShoppingItem("i1", "l1", "Mjölk"))
        family.saveChild(Child("c-ella", "Ella", contacts = listOf(Contact("Karin Svensson", "Simlärare", phone = "070-123 45 67"))))
    }

    @Test
    fun aRecurringEventIsFoundOnceAtItsNextTime() = runTest {
        seed()
        val r = search.search("simskola")
        val hit = r.events.single()
        assertEquals("Simskola", hit.occurrence.event.title)
        assertTrue(hit.occurrence.start.date >= today, "the next swim, not one in the past")
        assertEquals(listOf("t1"), r.tasks.map { it.taskId }, "the to-do mentions simskolan in its note")
        assertEquals(listOf("Karin Svensson"), search.search("sim").contacts.map { it.name }, "found through \"Simlärare\"")
    }

    @Test
    fun recipesByIngredientItemsByNameAndÅÄÖFirst() = runTest {
        seed()
        val milk = search.search("mjolk")
        assertEquals(listOf("r2"), milk.recipes.map { it.recipeId }, "typed without dots")
        assertEquals(listOf("Mjölk"), milk.items.map { it.name })
        assertEquals("Veckohandling", milk.items.single().listName)
        assertEquals(listOf("r3", "r4"), search.search("ost").recipes.map { it.recipeId }, "Ostkaka before Östron")
        assertEquals(listOf("r4"), search.search("östron").recipes.map { it.recipeId })
        assertTrue(search.search("läkare").events.any { it.occurrence.event.id == "dentist" }, "inside the compound")
        assertTrue(search.search("   ").isEmpty)
        assertTrue(search.search("xyzzy").isEmpty)
    }
}

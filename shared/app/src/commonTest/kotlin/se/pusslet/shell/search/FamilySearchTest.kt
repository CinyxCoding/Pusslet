package se.pusslet.shell.search

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Contact
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.time.plusDays
import se.pusslet.data.usecase.FamilySearch
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FamilySearchTest {
    private fun search(g: TestGraph) = FamilySearch(g.calendar, g.recipes, g.tasks, g.shopping, g.family, g.clock, g.zone)

    private suspend fun TestGraph.seed() {
        seedFamily()
        val today = this.today
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
        tasks.save(FamilyTask("t1", "Köp simglasögon", note = "Till simskolan"))
        shopping.saveList(ShoppingList("l1", "Veckohandling"))
        shopping.saveItem(ShoppingItem("i1", "l1", "Mjölk"))
        family.saveChild(Child(ELLA, "Ella", contacts = listOf(Contact("Karin Svensson", "Simlärare", phone = "070-123 45 67"))))
    }

    @Test
    fun aRecurringEventIsFoundOnceAtItsNextTime() = viewModelTest {
        val g = TestGraph()
        g.seed()
        val r = search(g).search("simskola")
        val hit = r.events.single()
        assertEquals("Simskola", hit.occurrence.event.title)
        assertTrue(hit.occurrence.start.date >= g.today, "the next swim, not one in the past")
        assertEquals(listOf("t1"), r.tasks.map { it.taskId }, "the to-do mentions simskolan in its note")
        assertEquals(
            listOf("Karin Svensson"),
            search(g).search("sim").contacts.map { it.name },
            "a contact is found through the relation \"Simlärare\"",
        )
    }

    @Test
    fun recipesAreFoundByIngredientAndItemsByName() = viewModelTest {
        val g = TestGraph()
        g.seed()
        val milk = search(g).search("mjolk")
        assertEquals(listOf("r2"), milk.recipes.map { it.recipeId }, "typed without dots")
        assertEquals(listOf("Mjölk"), milk.items.map { it.name })
        assertEquals("Veckohandling", milk.items.single().listName)
        assertTrue(search(g).search("läkare").events.any { it.occurrence.event.id == "dentist" }, "inside the compound")
        assertTrue(search(g).search("   ").isEmpty)
        assertTrue(search(g).search("xyzzy").isEmpty)
    }

    @Test
    fun theViewModelSearchesAsYouType() = viewModelTest {
        val g = TestGraph()
        g.seed()
        val vm = SearchViewModel(search(g), g.prefs, g.clock, g.zone)
        collectInBackground(vm.state)
        vm.setQuery("fisk")
        val state = vm.state.first { it.query == "fisk" }
        assertEquals(listOf("Fiskgratäng"), state.results.recipes.map { it.title })
        assertEquals(g.today, state.today)
    }
}

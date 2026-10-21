package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Recipe
import se.pusslet.core.time.isoWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealKindTest {
    private val monday = LocalDate(2026, 10, 12)
    private val tuesday = LocalDate(2026, 10, 13)

    /** The same settings as the data layer's DomainJson (shared/data sync/DocumentStore.kt). */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun anOldEntryWithoutKindOrCookIsCooked() {
        val old = """{"date":"2026-10-12","slot":"dinner","recipeId":"r1","title":"Tacos","cookDouble":false,"note":""}"""
        val entry = json.decodeFromString(MealEntry.serializer(), old)
        assertEquals(MealKind.Cooked, entry.kind)
        assertNull(entry.cookId)
        assertEquals("Tacos", entry.title)
    }

    @Test
    fun anOldWeekDocumentStillDecodes() {
        val old = """{"week":{"weekYear":2026,"week":42},"entries":{"2026-10-12_Dinner":{"date":"2026-10-12","title":"Pizza"}}}"""
        val week = json.decodeFromString(MealPlanWeek.serializer(), old)
        assertEquals(monday.isoWeek, week.week)
        val entry = week.entry(monday)
        assertEquals(MealKind.Cooked, entry?.kind)
        assertNull(entry?.cookId)
    }

    @Test
    fun kindsUseStableNames() {
        val takeaway = MealEntry(monday, title = "Pizza", kind = MealKind.Takeaway)
        val text = json.encodeToString(MealEntry.serializer(), takeaway)
        assertTrue("\"kind\":\"takeaway\"" in text, text)
        val none = json.encodeToString(MealEntry.serializer(), takeaway.copy(kind = MealKind.NoCooking))
        assertTrue("\"kind\":\"none\"" in none, none)
        val cooked = json.encodeToString(MealEntry.serializer(), takeaway.copy(kind = MealKind.Cooked, cookId = "m-johan"))
        assertTrue("\"kind\":\"cooked\"" in cooked && "\"cookId\":\"m-johan\"" in cooked, cooked)
        assertEquals(takeaway, json.decodeFromString(MealEntry.serializer(), text))
    }

    @Test
    fun aKindFromANewerAppReadsAsCooked() {
        val newer = """{"date":"2026-10-12","title":"Grill","kind":"barbecue"}"""
        assertEquals(MealKind.Cooked, json.decodeFromString(MealEntry.serializer(), newer).kind)
    }

    @Test
    fun takeawayAndNoCookingKeepNoRecipeOrCook() {
        val mistake = MealEntry(
            monday,
            recipeId = "r1",
            title = "Pizza",
            servings = 6,
            cookDouble = true,
            kind = MealKind.Takeaway,
            cookId = "m-johan",
        )
        assertFalse(mistake.cooksRecipe, "takeaway never needs ingredients, even with a recipe id")
        val clean = mistake.normalized()
        assertNull(clean.recipeId)
        assertNull(clean.servings)
        assertNull(clean.cookId)
        assertFalse(clean.cookDouble)
        assertEquals("Pizza", clean.title)
        assertFalse(mistake.copy(kind = MealKind.NoCooking).cooksRecipe)

        val cooked = MealEntry(monday, recipeId = "r1", title = "Tacos", cookId = "m-johan")
        assertTrue(cooked.cooksRecipe)
        assertEquals(cooked, cooked.normalized())
        assertFalse(cooked.copy(leftoversFrom = monday).cooksRecipe, "leftovers need no shopping")
        assertFalse(cooked.copy(recipeId = null).cooksRecipe)
    }

    @Test
    fun takeawayIsNotABusyEveningNeedingQuickFood() {
        assertTrue(MealSuggester.needsQuickDinner(busy = true, kind = null))
        assertTrue(MealSuggester.needsQuickDinner(busy = true, kind = MealKind.Cooked))
        assertFalse(MealSuggester.needsQuickDinner(busy = true, kind = MealKind.Takeaway))
        assertFalse(MealSuggester.needsQuickDinner(busy = true, kind = MealKind.NoCooking))
        assertFalse(MealSuggester.needsQuickDinner(busy = false, kind = null))
    }

    @Test
    fun takeawayYesterdayDoesNotCountForVarietyOrFreshness() {
        val fish = Recipe("fish", "Ugnsbakad lax", mainComponent = MainComponent.Fish, totalMinutes = 30)
        val pasta = Recipe("pasta", "Pasta med pesto", mainComponent = MainComponent.Pasta, totalMinutes = 15)
        val fresh = MealSuggester.suggest(tuesday, listOf(fish, pasta), history = emptyList())

        // Yesterday was takeaway, saved with the lax recipe by mistake: neither "made recently" nor "same as yesterday".
        listOf(MealKind.Takeaway, MealKind.NoCooking).forEach { kind ->
            val history = listOf(MealEntry(monday, recipeId = "fish", title = "Ugnsbakad lax", kind = kind))
            val s = MealSuggester.suggest(tuesday, listOf(fish, pasta), history)
            assertEquals(fresh.map { it.recipe.id to it.score }, s.map { it.recipe.id to it.score }, "$kind")
            assertTrue(s.none { SuggestionReason.VariesTheWeek in it.reasons }, "$kind does not vary the week")
            assertTrue(SuggestionReason.NotRecently in s.first { it.recipe.id == "fish" }.reasons)
        }

        // The same dinner cooked yesterday does count: lax is pushed down and pasta varies the week.
        val cooked = MealSuggester.suggest(
            tuesday,
            listOf(fish, pasta),
            listOf(MealEntry(monday, recipeId = "fish", title = "Ugnsbakad lax")),
        )
        assertEquals("pasta", cooked.first().recipe.id)
        assertTrue(SuggestionReason.VariesTheWeek in cooked.first().reasons)
    }

    @Test
    fun takeawayWithADishNameIsNotYesterdaysDish() {
        val pizza = Recipe("pizza", "Pizza", mainComponent = MainComponent.Other)
        val other = Recipe("soup", "Linssoppa", mainComponent = MainComponent.Vegetarian)
        val history = listOf(MealEntry(monday, slot = MealSlot.Dinner, title = "Pizza", kind = MealKind.Takeaway))
        val s = MealSuggester.suggest(tuesday, listOf(pizza, other), history)
        val none = MealSuggester.suggest(tuesday, listOf(pizza, other), emptyList())
        assertEquals(none.map { it.score }, s.map { it.score })
    }
}

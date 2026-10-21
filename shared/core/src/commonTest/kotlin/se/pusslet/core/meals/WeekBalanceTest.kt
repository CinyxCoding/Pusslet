package se.pusslet.core.meals

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.Recipe
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekBalanceTest {
    private val monday = LocalDate(2026, 10, 19)
    private val week = monday.isoWeek

    private fun day(offset: Int) = monday.plusDays(offset)

    // --- What a dish name tells ---

    @Test
    fun schoolMenuDishesFindTheirGroup() {
        assertEquals(FoodGroup.Fish, DishKeywords.foodGroup("Fiskgratäng med potatis"))
        assertEquals(FoodGroup.Fish, DishKeywords.foodGroup("Stekt strömming med potatismos"))
        assertEquals(FoodGroup.Poultry, DishKeywords.foodGroup("Kalkongryta med ris"))
        assertEquals(FoodGroup.RedMeat, DishKeywords.foodGroup("Pannbiff med lök"))
        assertEquals(FoodGroup.RedMeat, DishKeywords.foodGroup("Pasta carbonara"))
        assertEquals(FoodGroup.RedMeat, DishKeywords.foodGroup("Kalops med rödbetor"))
        assertEquals(FoodGroup.Vegetarian, DishKeywords.foodGroup("Pannkakor med sylt"))
        assertEquals(FoodGroup.Vegetarian, DishKeywords.foodGroup("Linssoppa"))
        assertNull(DishKeywords.foodGroup("Dagens soppa"))
        assertNull(DishKeywords.foodGroup(""))
    }

    @Test
    fun aVegetarianWordWinsButOnlyInTheFirstAlternative() {
        assertEquals(FoodGroup.Vegetarian, DishKeywords.foodGroup("Vegobullar med potatis"))
        assertEquals(FoodGroup.Vegetarian, DishKeywords.foodGroup("Vegetarisk lasagne"))
        assertEquals(FoodGroup.RedMeat, DishKeywords.foodGroup("Köttbullar / vegobullar"))
        assertEquals(FoodGroup.RedMeat, DishKeywords.foodGroup("Köttbullar eller vegobullar"))
        // Beans with meat are the meat.
        assertEquals(FoodGroup.Poultry, DishKeywords.foodGroup("Kyckling med bönor"))
    }

    @Test
    fun aSchoolLunchCountsItsFirstDish() {
        assertEquals(FoodGroup.Fish, WeekBalancer.lunchGroup(listOf("Fiskpinnar", "Falafel")))
        assertNull(WeekBalancer.lunchGroup(emptyList()))
    }

    @Test
    fun aRecipesGroupFromComponentTitleOrIngredients() {
        assertEquals(FoodGroup.Fish, WeekBalancer.recipeGroup(Recipe("r", "Mormors favorit", mainComponent = MainComponent.Fish)))
        assertEquals(FoodGroup.Poultry, WeekBalancer.recipeGroup(Recipe("r", "Kycklingwok")))
        val stew = Recipe("r", "Söndagsgryta", ingredients = listOf(Ingredient("nötkött"), Ingredient("morötter")))
        assertEquals(FoodGroup.RedMeat, WeekBalancer.recipeGroup(stew))
        val pesto = Recipe("r", "Pasta pesto", ingredients = listOf(Ingredient("pasta"), Ingredient("basilika"), Ingredient("parmesan")))
        assertEquals(FoodGroup.Vegetarian, WeekBalancer.recipeGroup(pesto))
        assertNull(WeekBalancer.recipeGroup(Recipe("r", "Fredagsmys")))
    }

    @Test
    fun noCookingSaysNothingAboutTheFood() {
        assertFalse(WeekBalancer.eaten(MealEntry(monday, title = "Middag hos mormor", kind = MealKind.NoCooking)))
        assertTrue(WeekBalancer.eaten(MealEntry(monday, title = "Pizza", kind = MealKind.Takeaway)))
    }

    // --- The week ---

    @Test
    fun childrenWithTheSameLunchesShareOneRow() {
        val lunch = mapOf(day(0) to FoodGroup.Fish, day(1) to FoodGroup.RedMeat, day(2) to null)
        val b = WeekBalancer.balance(
            week,
            dinners = mapOf(day(0) to FoodGroup.Vegetarian, day(1) to FoodGroup.Poultry, day(7) to FoodGroup.Fish),
            lunches = mapOf("ella" to lunch, "simon" to lunch, "otto" to mapOf(day(0) to FoodGroup.Vegetarian)),
        )
        assertEquals(2, b.eaters.size)
        val shared = b.eaters.first { "ella" in it.childIds }
        assertEquals(listOf("ella", "simon"), shared.childIds)
        assertEquals(1, shared.total[FoodGroup.Fish]) // next week's fish does not count
        assertEquals(1, shared.lunches.unknown)
        assertEquals(4, shared.total.known)
    }

    @Test
    fun withoutSchoolLunchesTheFamilysDinnersCount() {
        val b = WeekBalancer.balance(week, dinners = mapOf(day(0) to FoodGroup.Fish), lunches = emptyMap())
        assertEquals(listOf(EaterWeek(emptyList(), GroupCounts(), GroupCounts(mapOf(FoodGroup.Fish to 1)))), b.eaters)
        assertTrue(WeekBalancer.balance(week, emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun theAdviceDecidesWhatTheWeekWants() {
        val lunches = mapOf("ella" to mapOf(day(0) to FoodGroup.RedMeat, day(1) to FoodGroup.RedMeat, day(2) to FoodGroup.Fish))
        val b = WeekBalancer.balance(week, mapOf(day(0) to FoodGroup.RedMeat), lunches)
        assertEquals(setOf(FoodGroup.Fish, FoodGroup.Vegetarian), b.needs.wanted)
        assertTrue(b.needs.lessRedMeat)
        // Two fish and two vegetarian meals, little meat: nothing to lean towards.
        val balanced = WeekBalancer.balance(
            week,
            mapOf(day(0) to FoodGroup.Fish, day(1) to FoodGroup.Vegetarian),
            mapOf("ella" to mapOf(day(2) to FoodGroup.Fish, day(3) to FoodGroup.Vegetarian, day(4) to FoodGroup.RedMeat)),
        )
        assertTrue(balanced.needs.isEmpty)
    }

    @Test
    fun oneChildBelowTheAdviceIsEnough() {
        val b = WeekBalancer.balance(
            week,
            mapOf(day(0) to FoodGroup.Fish, day(1) to FoodGroup.Vegetarian, day(2) to FoodGroup.Vegetarian),
            mapOf("ella" to mapOf(day(3) to FoodGroup.Fish), "simon" to mapOf(day(3) to FoodGroup.Poultry)),
        )
        assertEquals(setOf(FoodGroup.Fish), b.needs.wanted)
    }

    // --- Suggestions ---

    private val salmon = Recipe("r-lax", "Ugnslax", mainComponent = MainComponent.Fish)
    private val tacos = Recipe("r-taco", "Tacos", mainComponent = MainComponent.MincedMeat)
    private val soup = Recipe("r-soppa", "Linssoppa", mainComponent = MainComponent.Vegetarian)
    private val chicken = Recipe("r-kyck", "Kycklinggryta", mainComponent = MainComponent.Chicken)

    @Test
    fun aDaysSuggestionsLeanTowardsWhatTheWeekLacks() {
        val needs = WeekNeeds(setOf(FoodGroup.Fish), lessRedMeat = true)
        val list = MealSuggester.suggest(day(2), listOf(tacos, chicken, salmon), emptyList(), week = needs)
        assertEquals(salmon, list.first().recipe)
        assertTrue(SuggestionReason.FishThisWeek in list.first().reasons)
        assertTrue(SuggestionReason.LessRedMeat in list.first { it.recipe == chicken }.reasons)
        assertEquals(tacos, list.last().recipe)
        // A balanced week changes nothing.
        val plain = MealSuggester.suggest(day(2), listOf(tacos, chicken, salmon), emptyList())
        assertTrue(plain.none { s -> s.reasons.any { it in WEEK_REASONS } })
    }

    @Test
    fun theWeekIsFilledAroundTheSchoolLunches() {
        // Fish and meat at school on Monday to Wednesday: the evenings bring vegetarian food and fish.
        val lunches = mapOf("ella" to mapOf(day(0) to FoodGroup.RedMeat, day(1) to FoodGroup.RedMeat, day(2) to FoodGroup.RedMeat))
        val proposals = WeekFiller.fill(
            days = (0..3).map(::day),
            recipes = listOf(tacos, salmon, soup, chicken),
            history = emptyList(),
            planned = emptyMap(),
            lunchGroups = lunches,
        )
        assertEquals((0..3).map(::day), proposals.map { it.date })
        val picks = proposals.map { it.options.first().recipe }
        assertEquals(picks.size, picks.toSet().size) // never the same recipe twice
        assertTrue(salmon in picks && soup in picks)
        assertEquals(tacos, picks.last()) // red meat is left for when nothing else is
        assertTrue(proposals.all { it.options.size <= WeekFiller.OPTIONS })
    }

    @Test
    fun noRecipesNoProposals() {
        assertTrue(WeekFiller.fill(listOf(monday), emptyList(), emptyList(), emptyMap(), emptyMap()).isEmpty())
    }

    private companion object {
        val WEEK_REASONS = setOf(SuggestionReason.FishThisWeek, SuggestionReason.VegetarianThisWeek, SuggestionReason.LessRedMeat)
    }
}

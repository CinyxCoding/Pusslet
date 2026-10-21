package se.pusslet.core.shopping

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ItemSource
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealKind
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShoppingListGeneratorTest {
    private val monday = LocalDate(2026, 10, 12)
    private val now = Instant.parse("2026-10-11T18:00:00Z")

    private fun recipe(id: String, title: String, servings: Int, vararg lines: String) =
        Recipe(id, title, servings, ingredients = lines.map { IngredientParser.parse(it) })

    private val kottfarssas = recipe(
        "r1",
        "Köttfärssås",
        4,
        "500 g nötfärs",
        "1 gul lök",
        "2 vitlöksklyftor",
        "1 burk krossade tomater",
        "2 msk tomatpuré",
        "1 dl grädde",
        "salt",
        "peppar",
    )
    private val kycklinggryta = recipe(
        "r2",
        "Kycklinggryta",
        4,
        "600 g kycklingfilé",
        "1 gul lök",
        "2 dl matlagningsgrädde",
        "1 msk curry",
        "3 dl ris",
    )
    private val pannkakor = recipe("r3", "Pannkakor", 4, "3 ägg", "6 dl mjölk", "2,5 dl vetemjöl", "1 krm salt", "2 msk smör")
    private val laxpasta = recipe("r4", "Laxpasta", 4, "400 g laxfilé", "400 g pasta", "2 msk grädde", "1 citron", "1 kruka dill")
    private val linssoppa = recipe(
        "r5",
        "Röd linssoppa",
        4,
        "2 dl röda linser",
        "2 gula lökar",
        "2 morötter",
        "1 burk krossade tomater",
        "1 liter vatten",
        "2 grönsaksbuljongtärningar",
    )
    private val recipes = listOf(kottfarssas, kycklinggryta, pannkakor, laxpasta, linssoppa).associateBy { it.id }

    private fun week() = listOf(
        MealEntry(monday, recipeId = "r1", title = "Köttfärssås"),
        MealEntry(monday.plusDays(1), recipeId = "r2", title = "Kycklinggryta"),
        MealEntry(monday.plusDays(2), recipeId = "r3", title = "Pannkakor", servings = 8),
        MealEntry(monday.plusDays(3), recipeId = "r4", title = "Laxpasta"),
        MealEntry(monday.plusDays(4), recipeId = "r5", title = "Röd linssoppa"),
    )

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    private fun generate(pantry: List<PantryItem> = emptyList()) =
        ShoppingListGenerator.lines(ShoppingListGenerator.demands(week(), recipes, defaultServings = 4), pantry)

    private fun List<GeneratedLine>.line(name: String) = single { it.key == ShoppingNames.canonical(name) }

    @Test
    fun fiveRecipesMergeIntoOneSortedList() {
        val lines = generate()
        // Same item across recipes, with singular/plural and pieces.
        val onion = lines.line("gul lök")
        assertEquals(4.0, onion.primary?.value)
        assertEquals(MeasureUnit.St, onion.primary?.unit)
        assertEquals(3, onion.sources.size)
        assertEquals(2.0, lines.line("krossade tomater").primary?.value)
        assertEquals(MeasureUnit.Burk, lines.line("krossade tomater").primary?.unit)
        // 1 dl + 2 msk = 1,3 dl; matlagningsgrädde stays its own product.
        val cream = lines.line("grädde")
        assertEquals(MeasureUnit.Dl, cream.primary?.unit)
        assertEquals(1.3, cream.primary?.value ?: 0.0, 0.001)
        assertEquals(2.0, lines.line("matlagningsgrädde").primary?.value)
        // Servings scale: pancakes for 8.
        assertEquals(6.0, lines.line("ägg").primary?.value)
        assertEquals(12.0, lines.line("mjölk").primary?.value)
        assertEquals(MeasureUnit.Dl, lines.line("mjölk").primary?.unit)
        // Salt, pepper and water are not shopping.
        assertTrue(lines.none { it.key in setOf("salt", "peppar", "vatten") })

        val items = lines.mapIndexed { i, l ->
            ShoppingItem("i$i", "list", l.name, l.primary?.value, l.primary?.unit, aisle = l.aisle, sources = l.sources)
        }
        val groups = ShoppingListGenerator.groupForStore(items)
        val order = groups.map { it.aisle }
        assertEquals(order.sortedBy { Aisle.defaultOrder.indexOf(it) }, order, "aisles follow the walking order")
        assertEquals(Aisle.Produce, groups.first().aisle)

        fun aisleOf(name: String) = items.single { ShoppingNames.canonical(it.name) == ShoppingNames.canonical(name) }.aisle
        assertEquals(Aisle.Produce, aisleOf("gul lök"))
        assertEquals(Aisle.Produce, aisleOf("morötter"))
        assertEquals(Aisle.Meat, aisleOf("nötfärs"))
        assertEquals(Aisle.Meat, aisleOf("kycklingfilé"))
        assertEquals(Aisle.Fish, aisleOf("laxfilé"))
        assertEquals(Aisle.Dairy, aisleOf("grädde"))
        assertEquals(Aisle.Dairy, aisleOf("mjölk"))
        assertEquals(Aisle.Eggs, aisleOf("ägg"))
        assertEquals(Aisle.Canned, aisleOf("krossade tomater"))
        assertEquals(Aisle.Pantry, aisleOf("pasta"))
    }

    @Test
    fun pantryIsDeducted() {
        val lines = generate(
            listOf(
                PantryItem("p1", "Gul lök", 3.0, MeasureUnit.St),
                PantryItem("p2", "Ris"),
                PantryItem("p3", "mjölk", 5.0, MeasureUnit.Dl),
            ),
        )
        assertEquals(1.0, lines.line("gul lök").primary?.value)
        assertTrue(lines.line("ris").askIfAtHome, "rice at home without amount: asked (\"Finns hemma?\"), never skipped")
        assertEquals(3.0, lines.line("ris").primary?.value)
        assertEquals(7.0, lines.line("mjölk").primary?.value)
    }

    private fun LocalDate.plusDaysSet(vararg offsets: Int) = offsets.mapTo(LinkedHashSet()) { plusDays(it) }

    private fun applied(list: List<ShoppingItem>, plan: ShoppingPlan): List<ShoppingItem> =
        list.filterNot { it.id in plan.remove }.map { item -> plan.update.firstOrNull { it.id == item.id } ?: item } + plan.add

    private fun linesFor(days: Set<LocalDate>) =
        ShoppingListGenerator.lines(ShoppingListGenerator.demands(week().filter { it.date in days }, recipes, 4))

    @Test
    fun chosenDaysMergeAndAreIdempotent() {
        val days = monday.plusDaysSet(0, 1)
        var n = 0
        val first = ShoppingListGenerator.plan("list", emptyList(), linesFor(days), days, { "g${n++}" }, now, null)
        assertEquals(2.0, first.add.single { it.name == "gul lök" }.quantity, "one onion on Monday, one on Tuesday")
        val list = applied(emptyList(), first)
        val again = ShoppingListGenerator.plan("list", list, linesFor(days), days, { "g${n++}" }, now, null)
        assertTrue(again.isEmpty, "running it twice changes nothing")
    }

    @Test
    fun daysThatAreNotChosenAreLeftAlone() {
        var n = 0
        val tuesday = monday.plusDaysSet(1)
        var list =
            applied(emptyList(), ShoppingListGenerator.plan("list", emptyList(), linesFor(tuesday), tuesday, { "g${n++}" }, now, null))
        // Monday and Wednesday: the range Monday..Wednesday would wrongly take Tuesday's share away.
        val chosen = monday.plusDaysSet(0, 2)
        val plan = ShoppingListGenerator.plan("list", list, linesFor(chosen), chosen, { "g${n++}" }, now, null)
        assertTrue(plan.remove.isEmpty(), "${plan.remove}")
        list = applied(list, plan)
        assertEquals(2.0, list.single { it.name == "gul lök" }.quantity, "Tuesday's onion plus Monday's")
        assertTrue(list.any { it.name == "matlagningsgrädde" }, "Tuesday's chicken stays")

        // Later the rest of the week: shares add up, and redoing the first days changes nothing.
        val rest = monday.plusDaysSet(3, 4)
        list = applied(list, ShoppingListGenerator.plan("list", list, linesFor(rest), rest, { "g${n++}" }, now, null))
        assertEquals(4.0, list.single { it.name == "gul lök" }.quantity)
        assertTrue(ShoppingListGenerator.plan("list", list, linesFor(chosen), chosen, { "g${n++}" }, now, null).isEmpty)
    }

    @Test
    fun untickedIngredientsAreLeftOutAndManualItemsAlwaysStay() {
        val days = monday.plusDaysSet(0)
        var n = 0
        val manual = ShoppingItem("manual", "list", "Kaffe", 1.0, MeasureUnit.Pkt)
        val typedOnion = ShoppingItem("onion", "list", "Gul lök", 2.0, MeasureUnit.St)
        var list = applied(
            listOf(manual, typedOnion),
            ShoppingListGenerator.plan("list", listOf(manual, typedOnion), linesFor(days), days, { "g${n++}" }, now, null),
        )
        assertEquals(3.0, list.single { it.id == "onion" }.quantity)

        // Unticked in the preview: "nötfärs" (at home) and "gul lök".
        val chosen = linesFor(days).filter { it.key !in setOf("nötfärs", "gul lök") }
        val plan = ShoppingListGenerator.plan("list", list, chosen, days, { "g${n++}" }, now, null)
        assertTrue(plan.add.isEmpty())
        assertEquals(listOf(list.single { it.name == "nötfärs" }.id), plan.remove, "only what was generated for it goes")
        list = applied(list, plan)
        assertEquals(2.0, list.single { it.id == "onion" }.quantity, "the typed onions stay")
        assertTrue(list.any { it.id == "manual" })
        assertTrue(list.none { it.name == "nötfärs" })
    }

    @Test
    fun roundedUpPiecesDoNotGrowWhenRunAgain() {
        // Two at home: half a can of tomatoes, rounded up to one can.
        val days = monday.plusDaysSet(0)
        val lines = ShoppingListGenerator.lines(ShoppingListGenerator.demands(week().filter { it.date in days }, recipes, 2))
        var n = 0
        val list = applied(emptyList(), ShoppingListGenerator.plan("list", emptyList(), lines, days, { "g${n++}" }, now, null))
        assertEquals(1.0, list.single { it.name == "krossade tomater" }.quantity)
        assertTrue(ShoppingListGenerator.plan("list", list, lines, days, { "g${n++}" }, now, null).isEmpty)
        // Someone types one more can: still kept apart from the generated one.
        val more = list.map { if (it.name == "krossade tomater") it.copy(quantity = 2.0) else it }
        assertTrue(ShoppingListGenerator.plan("list", more, lines, days, { "g${n++}" }, now, null).isEmpty)
    }

    @Test
    fun onlyCookedRecipesNeedShopping() {
        val entries = listOf(
            MealEntry(monday, recipeId = "r1", title = "Köttfärssås"),
            MealEntry(monday.plusDays(1), title = "Pizza ute"),
            MealEntry(monday.plusDays(2), recipeId = "r3", title = "Hämtmat", kind = MealKind.Takeaway),
            MealEntry(monday.plusDays(3), recipeId = "r4", title = "Hos mormor", kind = MealKind.NoCooking),
            MealEntry(monday.plusDays(4), recipeId = "r1", title = "Rester", leftoversFrom = monday),
        )
        assertEquals(listOf("r1"), ShoppingListGenerator.demands(entries, recipes, 4).map { it.recipe.id })
        assertEquals(1, ShoppingListGenerator.demands(entries, recipes, 4).size)
    }

    @Test
    fun cookDoubleDoublesTheDinnerAndLeftoversAddNothing() {
        val entries = listOf(
            MealEntry(monday, recipeId = "r1", title = "Köttfärssås", cookDouble = true),
            MealEntry(monday.plusDays(1), title = "Köttfärssås", leftoversFrom = monday, slot = se.pusslet.core.model.MealSlot.Lunch),
        )
        val lines = ShoppingListGenerator.lines(ShoppingListGenerator.demands(entries, recipes, 4))
        assertEquals(1000.0, lines.line("nötfärs").primary?.convertTo(MeasureUnit.G)?.value)
        assertEquals(1, lines.line("nötfärs").sources.size)
    }

    @Test
    fun regeneratingKeepsManualAmountsAndCheckedItems() {
        val dates = monday..monday.plusDays(6)
        var n = 0
        val first = ShoppingListGenerator.plan("list", emptyList(), generate(), dates, { "g${n++}" }, now, "m-sara")
        assertTrue(first.update.isEmpty() && first.remove.isEmpty())
        var list = first.add

        // Someone adds 2 dl milk by hand on top of the generated 12 dl, and the eggs are already in the cart.
        list = list.map { item ->
            when (ShoppingNames.canonical(item.name)) {
                "mjölk" -> item.copy(quantity = 14.0)
                "ägg" -> item.copy(checked = true)
                else -> item
            }
        } + ShoppingItem("manual", "list", "Kaffe", 1.0, MeasureUnit.Pkt)

        // The plan changes: pancakes are cancelled.
        val newWeek = week().filterNot { it.recipeId == "r3" }
        val lines = ShoppingListGenerator.lines(ShoppingListGenerator.demands(newWeek, recipes, 4))
        val second = ShoppingListGenerator.plan("list", list, lines, dates, { "g${n++}" }, now, "m-sara")

        val milk = second.update.single { it.name == "mjölk" }
        assertEquals(2.0, milk.quantity, "only the hand-typed 2 dl remain")
        assertTrue(milk.sources.isEmpty())
        assertTrue(second.remove.none { it == "manual" })
        assertTrue(list.single { it.name == "ägg" }.id !in second.remove, "checked items are never touched")
        val flour = list.single { it.name == "vetemjöl" }
        assertTrue(flour.id in second.remove, "generated-only item disappears")
        assertTrue(second.add.isEmpty())

        // Running it again changes nothing.
        val applied = list.filterNot { it.id in second.remove }.map { item -> second.update.firstOrNull { it.id == item.id } ?: item }
        val third = ShoppingListGenerator.plan("list", applied, lines, dates, { "g${n++}" }, now, "m-sara")
        assertTrue(third.isEmpty, "a list that already matches the plan needs no writes: ${third.update.map { it.name }}")
    }

    @Test
    fun manualItemWithSameNameGetsTheGeneratedAmountAdded() {
        val existing = listOf(ShoppingItem("x", "list", "Gul lök", 1.0, MeasureUnit.St))
        val plan = ShoppingListGenerator.plan("list", existing, generate(), monday..monday.plusDays(6), { "n" }, now, null)
        val onion = plan.update.single { it.id == "x" }
        assertEquals(5.0, onion.quantity)
        assertEquals(3, onion.sources.size)
    }

    @Test
    fun itemsWithoutAmountsStayWithoutAmounts() {
        val r = recipe("r9", "Tacos", 4, "tacokrydda", "1 st gurka")
        val lines = ShoppingListGenerator.lines(
            ShoppingListGenerator.demands(
                listOf(MealEntry(monday, recipeId = "r9", title = "Tacos")),
                mapOf(
                    "r9" to r,
                ),
                4,
            ),
        )
        assertNull(lines.line("tacokrydda").primary)
        val sources = lines.line("gurka").sources
        assertEquals(listOf(ItemSource("r9", monday, "Tacos", 1.0, MeasureUnit.St)), sources)
    }
}

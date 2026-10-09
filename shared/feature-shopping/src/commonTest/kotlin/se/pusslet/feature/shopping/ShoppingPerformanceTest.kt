package se.pusslet.feature.shopping

import se.pusslet.core.model.Aisle
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.testing.TestGraph
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A big list (party shopping, a month of meal plans) must stay instant: 600 items through the real repositories,
 * grouped in walking order, and a tick-off reflected at once. Budgets are generous so slow CI machines do not flake;
 * they catch accidental quadratic work, not micro-regressions.
 */
class ShoppingPerformanceTest {
    @Test
    fun sixHundredItemsBuildAndReactQuickly() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.shopping.saveList(ShoppingList("big", "Kalaset"))
        val aisles = Aisle.entries
        g.shopping.saveItems(
            (1..ITEMS).map { i -> ShoppingItem("i$i", "big", "Vara $i", aisle = aisles[i % aisles.size]) },
        )

        fun vm() = ShoppingViewModel(g.shopping, g.family, g.prefs, g.products, g.sessions, g.ids, g.clock, tickMillis = 0)

        // Warm up once so the measurement is the work itself, not the JVM compiling it under a busy build.
        val warmUp = vm()
        collectInBackground(warmUp.state)
        assertEquals(ITEMS, warmUp.state.value.groups.sumOf { it.items.size })

        val vm = vm()
        val built = TimeSource.Monotonic.markNow()
        collectInBackground(vm.state)
        val state = vm.state.value
        val buildTime = built.elapsedNow()
        assertEquals(ITEMS, state.groups.sumOf { it.items.size })
        assertTrue(buildTime < BUDGET, "building the list took $buildTime")

        val item = state.groups.first().items.first()
        val ticked = TimeSource.Monotonic.markNow()
        vm.setChecked(item, true)
        val tickTime = ticked.elapsedNow()
        assertEquals(ITEMS - 1, vm.state.value.groups.sumOf { it.items.size })
        assertTrue(tickTime < BUDGET, "ticking off took $tickTime")
    }

    private companion object {
        const val ITEMS = 600
        val BUDGET = 1_000.milliseconds
    }
}

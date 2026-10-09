package se.pusslet.feature.family

import se.pusslet.feature.family.wishlist.WishlistViewModel
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.ELLA
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WishlistViewModelTest {
    @Test
    fun parentsWriteTheListAndWhoBoughtWhatIsShown() = viewModelTest {
        val parent = TestGraph()
        parent.seedFamily()
        val vm = WishlistViewModel(parent.wishes, parent.family, parent.sessions)
        vm.load(ELLA)
        collectInBackground(vm.state)
        assertTrue(vm.state.value.canEdit, "parents write the list; helpers only mark things bought (firestore.rules)")
        vm.add("Lego Friends", "")
        vm.add("   ", "")
        vm.add("Rullskridskor", "https://example.se/skridsko")
        assertEquals(listOf("Lego Friends", "Rullskridskor"), vm.state.value.wishes.map { it.text })

        val lego = vm.state.value.wishes.first()
        parent.wishes.setTaken(lego, true)
        val bought = vm.state.value.wishes
        assertEquals(listOf("Rullskridskor", "Lego Friends"), bought.map { it.text }, "bought things move down")
        assertTrue(bought.last().taken)
        assertEquals(vm.state.value.names[bought.last().takenBy], "Sara")
        vm.remove(bought.first())
        assertFalse(vm.state.value.wishes.any { it.text == "Rullskridskor" })
    }
}

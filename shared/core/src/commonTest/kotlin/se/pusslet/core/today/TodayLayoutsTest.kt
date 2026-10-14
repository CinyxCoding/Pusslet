package se.pusslet.core.today

import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import se.pusslet.core.model.Role
import se.pusslet.core.today.TodayBlock.Dinner
import se.pusslet.core.today.TodayBlock.Handoffs
import se.pusslet.core.today.TodayBlock.Packing
import se.pusslet.core.today.TodayBlock.Shopping
import se.pusslet.core.today.TodayBlock.Tasks
import se.pusslet.core.today.TodayBlock.Timeline
import se.pusslet.core.today.TodayBlock.Upcoming
import se.pusslet.core.today.TodayBlock.Weather
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** "Anpassa Idag": defaults per role and how a stored layout is merged, moved, switched and stored (ADR-027). */
class TodayLayoutsTest {
    private val parent = TodayLayouts.defaultFor(Role.Parent)

    private fun TodayLayout.heroOrder() = hero.map { it.block }

    private fun TodayLayout.sectionOrder() = sections.map { it.block }

    private fun TodayLayout.on() = items.filter { it.visible }.map { it.block }

    private fun stored(order: List<String> = emptyList(), visible: Map<String, Boolean> = emptyMap(), memberId: String = "sara") =
        StoredTodayLayout(memberId = memberId, sectionOrder = order, visible = visible)

    @Test
    fun idsAreStableAscii() {
        assertEquals(
            listOf("handoffs", "packing", "dinner", "weather", "timeline", "tasks", "upcoming", "shopping"),
            TodayBlock.entries.map { it.id },
        )
        TodayBlock.entries.forEach { assertEquals(it, TodayBlock.fromId(it.id)) }
        assertNull(TodayBlock.fromId("widget"))
    }

    @Test
    fun parentDefaultIsMorgonkollenAndThreeSectionsWithShoppingOff() {
        assertEquals(listOf(Handoffs, Packing, Dinner, Weather), parent.heroOrder())
        assertTrue(parent.hero.all { it.visible })
        assertEquals(listOf(Timeline, Tasks, Upcoming, Shopping), parent.sectionOrder())
        assertEquals(listOf(Timeline, Tasks, Upcoming), parent.sections.filter { it.visible }.map { it.block })
        assertTrue(parent.hero.all { it.block.placement == Placement.Hero })
        assertTrue(parent.sections.all { it.block.placement == Placement.Section })
    }

    @Test
    fun caregiverAndChildHaveNoDinnerAndNoTimeline() {
        val caregiver = TodayLayouts.defaultFor(Role.Caregiver)
        assertEquals(listOf(Handoffs, Packing, Weather), caregiver.heroOrder())
        assertEquals(listOf(Tasks, Upcoming, Shopping), caregiver.sectionOrder())
        assertEquals(listOf(Handoffs, Packing, Weather, Tasks, Upcoming), caregiver.on())
        assertFalse(Dinner in caregiver)
        assertFalse(Timeline in caregiver)
        assertEquals(caregiver, TodayLayouts.defaultFor(Role.Child))
    }

    @Test
    fun nothingStoredOrAnotherMemberGivesTheDefault() {
        assertEquals(parent, TodayLayouts.resolve(Role.Parent, "sara", null))
        val saras = stored(order = listOf("upcoming"), visible = mapOf("tasks" to false))
        assertEquals(parent, TodayLayouts.resolve(Role.Parent, "johan", saras))
        assertEquals(parent, TodayLayouts.resolve(Role.Parent, null, saras))
    }

    @Test
    fun nothingStoredFollowsARoleChange() {
        assertEquals(TodayLayouts.defaultFor(Role.Caregiver), TodayLayouts.resolve(Role.Caregiver, "sara", null))
        // A stored parent layout keeps its switches but drops the blocks the new role does not have.
        val changed = TodayLayouts.resolve(
            Role.Caregiver,
            "sara",
            stored(order = listOf("upcoming", "timeline", "tasks"), visible = mapOf("dinner" to false, "packing" to false)),
        )
        assertEquals(listOf(Handoffs, Packing, Weather), changed.heroOrder())
        assertEquals(listOf(Upcoming, Tasks, Shopping), changed.sectionOrder())
        assertFalse(changed.isOn(Packing))
    }

    @Test
    fun storedChangesAreApplied() {
        val layout = TodayLayouts.resolve(
            Role.Parent,
            "sara",
            stored(order = listOf("upcoming", "timeline", "tasks", "shopping"), visible = mapOf("tasks" to false, "weather" to false)),
        )
        assertEquals(listOf(Upcoming, Timeline, Tasks, Shopping), layout.sectionOrder())
        assertEquals(listOf(Handoffs, Packing, Dinner, Weather), layout.heroOrder())
        assertEquals(listOf(Handoffs, Packing, Dinner, Upcoming, Timeline), layout.on())
    }

    @Test
    fun unknownAndDuplicateIdsAreIgnored() {
        val layout = TodayLayouts.resolve(
            Role.Parent,
            "sara",
            stored(
                order = listOf("modul", "tasks", "tasks", "weather", "timeline", "upcoming", "shopping"),
                visible = mapOf("widget" to true, "shopping" to true),
            ),
        )
        // "weather" is a Morgonkollen row, never a section.
        assertEquals(listOf(Tasks, Timeline, Upcoming, Shopping), layout.sectionOrder())
        assertTrue(layout.isOn(Shopping))
        assertEquals(8, layout.items.size)
    }

    @Test
    fun missingKnownSectionsGoToTheirDefaultPlaceWithTheDefaultSwitch() {
        // As if "timeline" and "shopping" had been added after this layout was stored.
        val layout = TodayLayouts.resolve(Role.Parent, "sara", stored(order = listOf("upcoming", "tasks")))
        assertEquals(listOf(Timeline, Upcoming, Tasks, Shopping), layout.sectionOrder())
        assertTrue(layout.isOn(Timeline))
        assertFalse(layout.isOn(Shopping))
        // Only unknown ids: the default order.
        assertEquals(parent.sectionOrder(), TodayLayouts.resolve(Role.Parent, "sara", stored(order = listOf("x", "y"))).sectionOrder())
    }

    @Test
    fun moveSwapsWithTheNeighbourAndDoesNothingAtTheEnds() {
        val down = TodayLayouts.move(parent, Timeline, up = false)
        assertEquals(listOf(Tasks, Timeline, Upcoming, Shopping), down.sectionOrder())
        assertEquals(parent.sectionOrder(), TodayLayouts.move(down, Timeline, up = true).sectionOrder())
        assertSame(parent, TodayLayouts.move(parent, Timeline, up = true))
        assertSame(parent, TodayLayouts.move(parent, Shopping, up = false))
        // Morgonkollen has a fixed order, and a block the role does not have cannot move.
        assertSame(parent, TodayLayouts.move(parent, Weather, up = true))
        val caregiver = TodayLayouts.defaultFor(Role.Caregiver)
        assertSame(caregiver, TodayLayouts.move(caregiver, Timeline, up = false))
    }

    @Test
    fun aHiddenSectionKeepsItsPlaceAndCanBeMovedPast() {
        val hidden = TodayLayouts.setVisible(parent, Tasks, false)
        assertEquals(parent.sectionOrder(), hidden.sectionOrder())
        assertFalse(hidden.isOn(Tasks))
        val moved = TodayLayouts.move(hidden, Upcoming, up = true)
        assertEquals(listOf(Timeline, Upcoming, Tasks, Shopping), moved.sectionOrder())
        assertFalse(moved.isOn(Tasks))
        val back = TodayLayouts.move(TodayLayouts.setVisible(moved, Tasks, true), Tasks, up = true)
        assertEquals(parent, back)
    }

    @Test
    fun setVisibleKeepsTheRowsAndIgnoresBlocksTheRoleLacks() {
        val off = TodayLayouts.setVisible(parent, Dinner, false)
        assertEquals(parent.heroOrder(), off.heroOrder())
        assertFalse(off.isOn(Dinner))
        val caregiver = TodayLayouts.defaultFor(Role.Caregiver)
        assertEquals(caregiver, TodayLayouts.setVisible(caregiver, Dinner, true))
        // All four off: the layout still lists them (the card disappears, the switches stay).
        val allOff = listOf(Handoffs, Packing, Dinner, Weather).fold(parent) { l, b -> TodayLayouts.setVisible(l, b, false) }
        assertEquals(4, allOff.hero.size)
        assertTrue(allOff.hero.none { it.visible })
    }

    @Test
    fun toStoredKeepsOnlyChangesAndIsNullForTheDefault() {
        assertNull(TodayLayouts.toStored(parent, "sara", Role.Parent))
        assertTrue(TodayLayouts.isDefault(parent, Role.Parent))

        val switched = TodayLayouts.setVisible(TodayLayouts.setVisible(parent, Tasks, false), Shopping, true)
        val onlySwitches = assertNotNull(TodayLayouts.toStored(switched, "sara", Role.Parent))
        assertEquals(StoredTodayLayout(1, "sara", emptyList(), mapOf("tasks" to false, "shopping" to true)), onlySwitches)
        assertFalse(TodayLayouts.isDefault(switched, Role.Parent))

        val moved = TodayLayouts.move(parent, Upcoming, up = true)
        val onlyOrder = assertNotNull(TodayLayouts.toStored(moved, "sara", Role.Parent))
        assertEquals(listOf("timeline", "upcoming", "tasks", "shopping"), onlyOrder.sectionOrder)
        assertEquals(emptyMap(), onlyOrder.visible)

        // Undoing a change by hand is the default again: nothing to store.
        assertNull(TodayLayouts.toStored(TodayLayouts.move(moved, Upcoming, up = false), "sara", Role.Parent))
        val switchedBack = TodayLayouts.setVisible(TodayLayouts.setVisible(switched, Tasks, true), Shopping, false)
        assertNull(TodayLayouts.toStored(switchedBack, "sara", Role.Parent))
    }

    @Test
    fun storedLayoutRoundTripsThroughResolve() {
        val custom = TodayLayouts.move(TodayLayouts.move(TodayLayouts.setVisible(parent, Tasks, false), Upcoming, true), Upcoming, true)
        val stored = TodayLayouts.toStored(custom, "sara", Role.Parent)
        assertEquals(custom, TodayLayouts.resolve(Role.Parent, "sara", stored))
    }

    @Test
    fun storedJsonAlwaysHasTheVersionAndToleratesUnknownFields() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = StoredTodayLayout.serializer().nullable
        val text = json.encodeToString(serializer, stored(visible = mapOf("tasks" to false)))
        assertTrue("\"version\":1" in text, text)
        assertEquals(stored(visible = mapOf("tasks" to false)), json.decodeFromString(serializer, text))
        val future = """{"version":2,"memberId":"sara","sectionOrder":["upcoming","agenda","timeline","tasks","shopping"],"x":1}"""
        val decoded = assertNotNull(json.decodeFromString(serializer, future))
        assertEquals(listOf(Upcoming, Timeline, Tasks, Shopping), TodayLayouts.resolve(Role.Parent, "sara", decoded).sectionOrder())
        assertNull(json.decodeFromString(serializer, "null"))
    }
}

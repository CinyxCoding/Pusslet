package se.pusslet.core.custody

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodyException
import se.pusslet.core.model.CustodyPatternType
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Household
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingReason
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.packing.PackingPlanner
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CustodyAndPackingTest {
    private val ahmed = Household("a", "Hos pappa", listOf("ahmed"))
    private val lisa = Household("b", "Hos mamma")

    private fun d(s: String) = LocalDate.parse(s)

    // Handover on Mondays; week 41 (Mon 2026-10-05) the children are with Ahmed.
    private val alternating = CustodySchedule(
        id = "c1",
        childIds = listOf("ella"),
        households = listOf(ahmed, lisa),
        pattern = CustodyPatternType.AlternatingWeeks,
        anchorDate = d("2026-10-05"),
    )

    @Test
    fun alternatingWeeks() {
        assertEquals("a", CustodyCalculator.householdOn(alternating, d("2026-10-05"))?.id)
        assertEquals("a", CustodyCalculator.householdOn(alternating, d("2026-10-11"))?.id)
        assertEquals("b", CustodyCalculator.householdOn(alternating, d("2026-10-12"))?.id)
        assertEquals("b", CustodyCalculator.householdOn(alternating, d("2026-10-18"))?.id)
        assertEquals("a", CustodyCalculator.householdOn(alternating, d("2026-10-19"))?.id)
        // Before the anchor date the pattern continues backwards.
        assertEquals("b", CustodyCalculator.householdOn(alternating, d("2026-09-28"))?.id)
    }

    @Test
    fun alternatingAcrossNewYearAndWeek53() {
        // 2026-12-28 is Monday of week 53: 12 weeks after the anchor -> household a.
        assertEquals("a", CustodyCalculator.householdOn(alternating, d("2026-12-28"))?.id)
        assertEquals("b", CustodyCalculator.householdOn(alternating, d("2027-01-04"))?.id)
    }

    @Test
    fun twoTwoFiveFive() {
        val s = alternating.copy(pattern = CustodyPatternType.TwoTwoFiveFive)
        val days = (0 until 14).map { CustodyCalculator.householdOn(s, d("2026-10-05").plusDays(it))?.id }
        assertEquals("aabbaaaaabbbbb".map { it.toString() }, days)
    }

    @Test
    fun twoTwoThreeAndCustom() {
        val s = alternating.copy(pattern = CustodyPatternType.TwoTwoThree)
        val days = (0 until 14).joinToString("") { CustodyCalculator.householdOn(s, d("2026-10-05").plusDays(it))?.id.orEmpty() }
        assertEquals("aabbaaabbaabbb", days)
        val custom = alternating.copy(pattern = CustodyPatternType.Custom, customCycle = listOf(0, 1, 1))
        assertEquals(
            "abbabb",
            (0 until 6).joinToString("") {
                CustodyCalculator.householdOn(custom, d("2026-10-05").plusDays(it))?.id.orEmpty()
            },
        )
    }

    @Test
    fun exceptionsOverrideThePattern() {
        val s = alternating.copy(exceptions = listOf(CustodyException(d("2026-10-26"), d("2026-10-30"), "a", "Höstlov hos pappa")))
        // Week 44 would normally be with household b.
        assertEquals("a", CustodyCalculator.householdOn(s, d("2026-10-28"))?.id)
        assertEquals("b", CustodyCalculator.householdOn(s, d("2026-10-31"))?.id)
    }

    @Test
    fun nextHandoverAndHandoverDay() {
        val h = assertNotNull(CustodyCalculator.nextHandover(alternating, d("2026-10-08")))
        assertEquals(d("2026-10-12"), h.date)
        assertEquals("a", h.from.id)
        assertEquals("b", h.to.id)
        assertTrue(CustodyCalculator.isHandoverDay(alternating, d("2026-10-12")))
        assertFalse(CustodyCalculator.isHandoverDay(alternating, d("2026-10-13")))
    }

    @Test
    fun periodsForCalendarBands() {
        val periods = CustodyCalculator.periods(alternating, d("2026-10-08"), d("2026-10-25"))
        assertEquals(listOf(d("2026-10-08"), d("2026-10-12"), d("2026-10-19")), periods.map { it.start })
        assertEquals(d("2026-10-25"), periods.last().endInclusive)
    }

    @Test
    fun myWeekYourWeek() {
        assertTrue(CustodyCalculator.isWithMember(alternating, d("2026-10-08"), "ahmed"))
        assertFalse(CustodyCalculator.isWithMember(alternating, d("2026-10-14"), "ahmed"))
    }

    private val ella = Child("ella", "Ella")
    private val simon = Child("simon", "Simon")

    @Test
    fun packingWeeklyRuleForOneChild() {
        val gym = PackingRule("r1", "Gympapåse", childId = "ella", trigger = PackingTrigger.Weekly(setOf(DayOfWeek.WEDNESDAY)))
        val items = PackingPlanner.itemsFor(d("2026-10-14"), listOf(ella, simon), listOf(gym), emptyList())
        assertEquals(listOf("Gympapåse"), items.getValue("ella").map { it.text })
        assertTrue(items.getValue("simon").isEmpty())
        assertTrue(PackingPlanner.itemsFor(d("2026-10-15"), listOf(ella), listOf(gym), emptyList()).getValue("ella").isEmpty())
    }

    @Test
    fun packingEveryOtherFriday() {
        val book = PackingRule("r2", "Bibliotekbok", trigger = PackingTrigger.Weekly(setOf(DayOfWeek.FRIDAY), 2, IsoWeek(2026, 41)))
        assertEquals(1, PackingPlanner.itemsFor(d("2026-10-09"), listOf(ella), listOf(book), emptyList()).getValue("ella").size)
        assertEquals(0, PackingPlanner.itemsFor(d("2026-10-16"), listOf(ella), listOf(book), emptyList()).getValue("ella").size)
        assertEquals(1, PackingPlanner.itemsFor(d("2026-10-23"), listOf(ella), listOf(book), emptyList()).getValue("ella").size)
    }

    @Test
    fun packingForTaggedEventAndOneOffItems() {
        val lunchbox = PackingRule("r3", "Matsäck", trigger = PackingTrigger.EventTag(EventTags.TRIP))
        val trip = Event(
            "e1",
            "Skogsutflykt",
            LocalDateTime.parse("2026-10-09T00:00"),
            LocalDateTime.parse("2026-10-10T00:00"),
            allDay = true,
            participantIds = listOf("simon"),
            tags = listOf("utflykt"),
            packingItems = listOf("Regnkläder"),
        )
        val occ = EventExpander.expand(listOf(trip), d("2026-10-09"), d("2026-10-09"), TimeZone.of("Europe/Stockholm"))
        val checks = mapOf(
            PackingChecks.docId(d("2026-10-09"), "simon") to PackingChecks(d("2026-10-09"), "simon", mapOf("rule:r3" to true)),
        )
        val items = PackingPlanner.itemsFor(d("2026-10-09"), listOf(ella, simon), listOf(lunchbox), occ, checks = checks)
        assertTrue(items.getValue("ella").isEmpty())
        val simonItems = items.getValue("simon")
        assertEquals(listOf("Matsäck", "Regnkläder"), simonItems.map { it.text })
        assertTrue(simonItems[0].checked)
        assertFalse(simonItems[1].checked)
        assertEquals(PackingReason.ForEvent("Skogsutflykt"), simonItems[0].reason)
    }

    @Test
    fun packingOnHandoverDays() {
        val bag = PackingRule("r4", "Mjukisdjuret Kanin", childId = "ella", trigger = PackingTrigger.Handover)
        assertEquals(
            1,
            PackingPlanner.itemsFor(d("2026-10-12"), listOf(ella), listOf(bag), emptyList(), listOf(alternating)).getValue("ella").size,
        )
        assertEquals(
            0,
            PackingPlanner.itemsFor(d("2026-10-13"), listOf(ella), listOf(bag), emptyList(), listOf(alternating)).getValue("ella").size,
        )
    }

    @Test
    fun handoverCardContainsOnlyWhatIsShared() {
        val h = assertNotNull(CustodyCalculator.nextHandover(alternating, d("2026-10-08")))
        val rules = listOf(PackingRule("r4", "Mjukisdjuret Kanin", "ella", PackingTrigger.Handover))
        val tasks = listOf(
            FamilyTask("t1", "Läs 20 min/dag", category = TaskCategory.Homework, due = d("2026-10-16")),
            FamilyTask("t2", "Betala elräkningen", category = TaskCategory.Household),
        )
        val football = Event(
            "f",
            "Fotboll",
            LocalDateTime.parse("2026-10-13T17:30"),
            LocalDateTime.parse("2026-10-13T18:30"),
            participantIds = listOf("ella"),
            rrule = "FREQ=WEEKLY;BYDAY=TU",
        )
        val occ = EventExpander.expand(listOf(football), h.date, d("2026-10-18"), TimeZone.of("Europe/Stockholm"))
        val card = HandoverCardBuilder.build(h, listOf(ella, simon), rules, tasks, occ, d("2026-10-18"))
        assertEquals(listOf("Mjukisdjuret Kanin"), card.bring)
        assertEquals(listOf("Läs 20 min/dag"), card.homework)
        assertEquals(listOf("Fotboll"), card.upcoming.map { it.title })
        assertEquals(listOf("Ella"), card.children.map { it.firstName })
    }
}

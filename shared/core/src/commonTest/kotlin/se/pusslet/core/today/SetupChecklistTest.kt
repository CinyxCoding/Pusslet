package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.Family
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** "Kom igång" (UX plan 4.4) for Sara, who created her family yesterday: Wednesday 14 October 2026. */
class SetupChecklistTest {
    private val today = LocalDate(2026, 10, 14)
    private val now = Instant.parse("2026-10-14T06:00:00Z")
    private val family = Family("f", "Familjen Berg", createdAt = now - 1.days)

    private val sara = Member("m-sara", "Sara", Role.Parent)
    private val johan = Member("m-johan", "Johan", Role.Parent)
    private val ella = Child("c-ella", "Ella", birthDate = LocalDate(2017, 3, 14))
    private val simon = Child("c-simon", "Simon", birthDate = LocalDate(2021, 7, 1))
    private val school = SchoolRef("Vasaskolan")

    private fun input(
        role: Role = Role.Parent,
        members: List<Member> = listOf(sara, johan),
        children: List<Child> = listOf(ella, simon),
        events: List<Event> = emptyList(),
        occurrences: List<EventOccurrence> = emptyList(),
        mealWeeks: List<MealPlanWeek> = emptyList(),
        hasShoppingItems: Boolean = false,
        canInvite: Boolean = true,
        family: Family? = this.family,
        now: Instant = this.now,
        demo: Boolean = false,
        hidden: Boolean = false,
    ) = SetupInput(
        today = today,
        now = now,
        role = role,
        me = sara.id,
        family = family,
        demo = demo,
        hidden = hidden,
        members = members,
        children = children,
        events = events,
        occurrences = occurrences,
        mealWeeks = mealWeeks,
        hasShoppingItems = hasShoppingItems,
        canInvite = canInvite,
    )

    private fun schoolDay(id: String, date: LocalDate, dropOff: Handoff? = null, pickUp: Handoff? = null, tag: String = "skola") =
        Event(
            id = id,
            title = "Skola",
            start = LocalDateTime(date, LocalTime(8, 15)),
            end = LocalDateTime(date, LocalTime(14, 30)),
            participantIds = listOf(ella.id),
            dropOff = dropOff,
            pickUp = pickUp,
            tags = listOf(tag),
        )

    private fun Event.occurrence(source: EventSource = EventSource.Family) = EventOccurrence(this, start, end, start.date, source)

    private val swimming = Event(
        id = "e-swim",
        title = "Simskola",
        start = LocalDateTime(today.plusDays(1), LocalTime(17, 30)),
        end = LocalDateTime(today.plusDays(1), LocalTime(18, 15)),
        participantIds = listOf(ella.id),
    )
    private val tacos = MealPlanWeek(today.isoWeek).with(MealEntry(today.plusDays(2), MealSlot.Dinner, title = "Tacos"))

    @Test
    fun aNewFamilyIsAskedForWhatTheDataLacks() {
        val items = SetupChecklist.items(input())

        assertEquals(
            listOf("school-c-ella", "school-c-simon", "invite-m-johan", "activity", "dinners", "shopping"),
            items.map { it.key },
        )
        assertTrue(items.none { it.done })
        assertFalse(assertIs<SetupItem.School>(items[0]).preschool, "Ella is 9: skola")
        assertTrue(assertIs<SetupItem.School>(items[1]).preschool, "Simon is 5: förskola")
    }

    @Test
    fun stepsTickThemselvesOffFromTheData() {
        val progress = assertNotNull(
            SetupChecklist.forIdag(
                input(
                    children = listOf(ella.copy(school = school), simon),
                    members = listOf(sara, johan.copy(uid = "u-johan")),
                    events = listOf(swimming),
                ),
            ),
        )

        assertEquals(listOf(true, false, true, true, false, false), progress.items.map { it.done })
        assertEquals(3, progress.done, "3 av 6 klart")
        assertEquals(6, progress.total)
        assertEquals(listOf("school-c-simon", "dinners", "shopping"), progress.next.map { it.key }, "only what is left")

        val more = SetupChecklist.items(input(mealWeeks = listOf(tacos), hasShoppingItems = true))
        assertTrue(more.first { it.key == "dinners" }.done)
        assertTrue(more.first { it.key == "shopping" }.done)
    }

    @Test
    fun atMostThreeRowsWithShowAllForTheRest() {
        val fresh = assertNotNull(SetupChecklist.forIdag(input()))
        assertEquals(SetupChecklist.MAX_VISIBLE, fresh.next.size)
        assertEquals(listOf("school-c-ella", "school-c-simon", "invite-m-johan"), fresh.next.map { it.key })
        assertTrue(fresh.hasMore)
        assertEquals("0 av 6", "${fresh.done} av ${fresh.total}")

        // Two steps left out of three: both are on the card, "Visa alla" still shows the ticked ones.
        val almost = assertNotNull(
            SetupChecklist.forIdag(
                input(children = emptyList(), members = listOf(sara), events = listOf(swimming), canInvite = false),
            ),
        )
        assertEquals(listOf("dinners", "shopping"), almost.next.map { it.key })
        assertEquals(1, almost.done)
        assertEquals(3, almost.total)
        assertTrue(almost.hasMore)
    }

    @Test
    fun goneWhenEverythingIsDone() {
        val done = input(
            children = listOf(ella.copy(school = school), simon.copy(school = school)),
            members = listOf(sara, johan.copy(uid = "u-johan")),
            events = listOf(swimming),
            mealWeeks = listOf(tacos),
            hasShoppingItems = true,
        )
        assertTrue(SetupChecklist.items(done).all { it.done })
        assertNull(SetupChecklist.forIdag(done))
    }

    @Test
    fun neverForCaregiversOrChildren() {
        assertNull(SetupChecklist.forIdag(input(role = Role.Caregiver)))
        assertNull(SetupChecklist.forIdag(input(role = Role.Child)))
        assertNotNull(SetupChecklist.forIdag(input(role = Role.Parent)))
    }

    @Test
    fun onlyForANewRealFamilyThatHasNotHiddenIt() {
        assertNull(SetupChecklist.forIdag(input(hidden = true)), "Dölj")
        assertNull(SetupChecklist.forIdag(input(demo = true)), "the example family has Börja på riktigt instead")
        assertNull(SetupChecklist.forIdag(input(family = null)))
        assertNull(SetupChecklist.forIdag(input(family = family.copy(createdAt = null))), "no creation time: not new")
        assertNotNull(SetupChecklist.forIdag(input(now = now + 26.days)))
        assertNull(SetupChecklist.forIdag(input(now = now + 27.days)), "four weeks after it was created")
    }

    @Test
    fun handoffsOnlyOnceThereAreSchoolDays() {
        assertTrue(SetupChecklist.items(input()).none { it is SetupItem.Handoffs }, "nothing to decide yet")

        val monday = schoolDay("e-mon", today.plusDays(5))
        val friday = schoolDay("e-fri", today.plusDays(2), dropOff = Handoff(johan.id))
        val open = SetupChecklist.items(input(occurrences = listOf(monday.occurrence(), friday.occurrence())))
            .filterIsInstance<SetupItem.Handoffs>().single()
        assertFalse(open.done)
        assertEquals("e-mon", open.next?.event?.id, "the first school day nobody takes")

        val decided = monday.copy(dropOff = Handoff(sara.id), pickUp = Handoff(johan.id))
        val done = SetupChecklist.items(input(occurrences = listOf(decided.occurrence(), friday.occurrence())))
            .filterIsInstance<SetupItem.Handoffs>().single()
        assertTrue(done.done)
    }

    @Test
    fun aPlannedHandoffWithNobodyStillNeedsItsPerson() {
        val preschool = schoolDay("e-pre", today.plusDays(1), dropOff = Handoff(sara.id), pickUp = Handoff(null), tag = "förskola")
        val item = SetupChecklist.items(input(occurrences = listOf(preschool.occurrence())))
            .filterIsInstance<SetupItem.Handoffs>().single()
        assertEquals("e-pre", item.next?.event?.id)
    }

    @Test
    fun schoolDaysFromASubscriptionOrThePastDoNotCount() {
        val subscribed = schoolDay("e-ics", today.plusDays(1)).occurrence(EventSource.Subscription)
        val yesterday = schoolDay("e-old", today.plusDays(-1)).occurrence()
        val items = SetupChecklist.items(input(occurrences = listOf(subscribed, yesterday)))
        assertTrue(items.none { it is SetupItem.Handoffs })
    }

    @Test
    fun invitesOnlyWithABackendAndNeverForMe() {
        assertTrue(SetupChecklist.items(input(canInvite = false)).none { it is SetupItem.Invite })

        val bengt = Member("m-bengt", "Bengt", Role.Caregiver)
        val invites = SetupChecklist.items(input(members = listOf(sara, johan, bengt))).filterIsInstance<SetupItem.Invite>()
        assertEquals(listOf("Johan", "Bengt"), invites.map { it.member.displayName })

        val joined = SetupChecklist.items(input(members = listOf(sara, johan.copy(joinedAt = now))))
            .filterIsInstance<SetupItem.Invite>().single()
        assertTrue(joined.done)
    }

    @Test
    fun schoolIsAskedForSchoolAgeChildrenAndUnknownAges() {
        val baby = Child("c-baby", "Alva", birthDate = today.plusDays(-200))
        val unknown = Child("c-noah", "Noah")
        val grown = Child("c-ida", "Ida", birthDate = LocalDate(2006, 1, 1))
        val schools = SetupChecklist.items(input(children = listOf(baby, unknown, grown))).filterIsInstance<SetupItem.School>()

        assertEquals(listOf("Noah"), schools.map { it.child.firstName }, "a baby has no preschool yet, Ida has left school")
        assertFalse(schools.single().preschool, "without an age it says skola")
    }

    @Test
    fun aSchoolBlockIsNotTheFirstActivity() {
        val schoolOnly = SetupChecklist.items(input(events = listOf(schoolDay("e-mon", today))))
        assertFalse(schoolOnly.first { it is SetupItem.Activity }.done)
        assertTrue(SetupChecklist.items(input(events = listOf(swimming))).first { it is SetupItem.Activity }.done)
    }
}

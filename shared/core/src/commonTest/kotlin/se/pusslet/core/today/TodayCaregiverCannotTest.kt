package se.pusslet.core.today

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * "Bengt kan inte hämta Simon 15:30 · Jag tar det" on the parents' Idag (UX plan Steg 3): morfar Bengt's open "Jag kan inte"
 * is a problem today and tomorrow, and the hand-off is then only there (ADR-028). Thursday 8 October 2026, 07:10.
 */
class TodayCaregiverCannotTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val thursday = LocalDate(2026, 10, 8)
    private val friday = thursday.plusDays(1)
    private val saturday = thursday.plusDays(2)
    private val people = listOf(
        FamilyPerson.of(Member(SARA, "Sara", Role.Parent)),
        FamilyPerson.of(Member(JOHAN, "Johan", Role.Parent)),
        FamilyPerson.of(Member(BENGT, "Bengt", Role.Caregiver)),
    )
    private val children = listOf(
        Child(SIMON, "Simon", birthDate = LocalDate(2022, 5, 2)),
        Child(ELLA, "Ella", birthDate = LocalDate(2017, 3, 14)),
    )

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private fun day(id: String, date: LocalDate, child: String, pickUp: String, time: LocalTime) = Event(
        id,
        "Förskola",
        at(date, 8),
        at(date, 16),
        participantIds = listOf(child),
        dropOff = Handoff(JOHAN, LocalTime(7, 50)),
        pickUp = Handoff(pickUp, time),
        tags = listOf("förskola"),
    )

    private val events = listOf(
        day("e-to", thursday, SIMON, BENGT, LocalTime(15, 30)),
        day("e-fre", friday, ELLA, BENGT, LocalTime(15, 45)),
        day("e-lor", saturday, SIMON, BENGT, LocalTime(15, 30)),
    )

    private fun cannot(eventId: String, date: LocalDate, from: String = BENGT) = HandoffRequest(
        id = "r-$eventId",
        eventId = eventId,
        occurrenceDate = date,
        kind = HandoffKind.PickUp,
        fromMemberId = from,
        createdAt = Instant.parse("2026-10-08T05:00:00Z"),
    )

    private fun plan(requests: List<HandoffRequest>, now: LocalTime = LocalTime(7, 10), list: List<Event> = events) = TodayPlanner.build(
        TodayInput(
            today = thursday,
            now = now,
            zone = zone,
            me = SARA,
            people = people + children.map(FamilyPerson::of),
            children = children,
            occurrences = EventExpander.expand(list, thursday, thursday.plusDays(3), zone),
            requests = requests,
        ),
    )

    @Test
    fun aCaregiversCannotMakeIsAProblemTodayAndTomorrow() {
        val plan = plan(listOf(cannot("e-to", thursday), cannot("e-fre", friday), cannot("e-lor", saturday)))

        assertEquals(listOf(thursday, friday), plan.cannotMake.map { it.date }, "Saturday is not yet a problem")
        val today = plan.cannotMake.first()
        assertEquals("Bengt", today.from?.name)
        assertEquals(HandoffKind.PickUp, today.kind)
        assertEquals(LocalTime(15, 30), today.time)
        assertEquals(listOf(SIMON), today.childIds)
    }

    @Test
    fun theHandoffIsThenOnlyUnderAttLosa() {
        val plan = plan(listOf(cannot("e-to", thursday)))

        // "Bengt hämtar Simon 15:30" is not in Morgonkollen while he says he cannot.
        assertTrue(plan.hero.handoffs.none { line -> line.kind == HandoffKind.PickUp && line.person?.id == BENGT })
        assertTrue(plan.hero.handoffs.any { it.kind == HandoffKind.DropOff }, "Johan's drop-off is still there")
        val timeline = plan.timelineFor(TodayLayouts.defaultFor(Role.Parent).copy(hero = emptyList()))
        assertTrue(timeline.none { it is TimelineItem.HandoffItem && it.kind == HandoffKind.PickUp })
    }

    @Test
    fun itIsSolvedOnceSomeoneTakesItOrGetsIt() {
        val taken = cannot("e-to", thursday).copy(status = RequestStatus.Accepted, respondedBy = JOHAN, appliedAt = Instant.DISTANT_PAST)
        assertTrue(plan(listOf(taken)).cannotMake.isEmpty())

        // Sara gave it to Johan in the event without answering: it is not Bengt's any more.
        val reassigned = events.map { if (it.id == "e-to") it.copy(pickUp = Handoff(JOHAN, LocalTime(15, 30))) else it }
        assertTrue(plan(listOf(cannot("e-to", thursday)), list = reassigned).cannotMake.isEmpty())
    }

    @Test
    fun onlyACaregiversOwnOpenNoCounts() {
        // A parent asking everyone is a question ("Kan någon hämta?"), not this problem; so is a question to one person.
        assertTrue(plan(listOf(cannot("e-to", thursday, from = SARA))).cannotMake.isEmpty())
        assertTrue(plan(listOf(cannot("e-to", thursday).copy(toMemberId = JOHAN))).cannotMake.isEmpty())
    }

    @Test
    fun aPassedHandoffIsNoLongerAProblem() {
        assertTrue(plan(listOf(cannot("e-to", thursday)), now = LocalTime(16, 0)).cannotMake.isEmpty())
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val SIMON = "c-simon"
        const val ELLA = "c-ella"
    }
}

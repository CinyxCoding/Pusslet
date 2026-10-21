package se.pusslet.core.tasks

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.IsoWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Så har vi delat" on Tuesday 20 October 2026: weeks 40–43, from Monday 28 September. */
class TaskDistributionTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 20)
    private val sara = FamilyPerson(SARA, "Sara", MemberColor.Sage, isChild = false, role = Role.Parent)
    private val johan = FamilyPerson(JOHAN, "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent)
    private val bengt = FamilyPerson(BENGT, "Bengt", MemberColor.Slate, isChild = false, role = Role.Caregiver)
    private val ella = FamilyPerson(ELLA, "Ella", MemberColor.Rose, isChild = true)
    private val people = listOf(sara, johan, bengt, ella)

    private fun compute(
        tasks: List<FamilyTask> = emptyList(),
        events: List<Event> = emptyList(),
        requests: List<HandoffRequest> = emptyList(),
        today: LocalDate = tuesday,
    ): Distribution {
        val from = today.minusDays(TaskDistribution.WEEKS * 7)
        val occurrences = EventExpander.expand(events, from, today.plusDaysBy(7), zone, EventSource.Family)
        return TaskDistribution.compute(today, zone, people, tasks, occurrences, requests)
    }

    private fun Distribution.of(id: String, kind: ShareKind) = shares.first { it.person.id == id }.count(kind)

    @Test
    fun theLastFourIsoWeeksUpToToday() {
        val d = compute()
        assertEquals(IsoWeek(2026, 40), d.fromWeek)
        assertEquals(IsoWeek(2026, 43), d.toWeek)
        assertEquals(LocalDate(2026, 9, 28), d.from)
        assertEquals(tuesday, d.to)
        // Children are never part of the adults' share.
        assertEquals(listOf(SARA, JOHAN, BENGT), d.shares.map { it.person.id })
        assertTrue(d.isEmpty)
    }

    @Test
    fun weekEdgesFollowTheLocalDay() {
        val task = { id: String, at: String -> FamilyTask(id, "Uppgift", done = true, doneBy = SARA, doneAt = Instant.parse(at)) }
        val d = compute(
            listOf(
                // Sunday 27 September 23:30 in Stockholm (summer time): week 39, not counted.
                task("a", "2026-09-27T21:30:00Z"),
                // Monday 28 September 00:10 in Stockholm: week 40.
                task("b", "2026-09-27T22:10:00Z"),
                // Today counts, tomorrow not (a tick in the future is odd data).
                task("c", "2026-10-20T10:00:00Z"),
                task("d", "2026-10-21T10:00:00Z"),
                // Not done: never counted.
                FamilyTask("e", "Öppen", doneBy = SARA, doneAt = Instant.parse("2026-10-19T10:00:00Z")),
                // Done without a time: we cannot say when, so it is not counted.
                FamilyTask("f", "Gammal", done = true, doneBy = SARA),
            ),
        )
        assertEquals(2, d.of(SARA, ShareKind.Tasks))
    }

    @Test
    fun overTheNewYearWeek53IsIncluded() {
        val d = compute(
            listOf(FamilyTask("a", "Uppgift", done = true, doneBy = JOHAN, doneAt = Instant.parse("2026-12-30T12:00:00Z"))),
            today = LocalDate(2027, 1, 5),
        )
        assertEquals(IsoWeek(2026, 51), d.fromWeek)
        assertEquals(IsoWeek(2027, 1), d.toWeek)
        assertEquals(LocalDate(2026, 12, 14), d.from)
        assertEquals(1, d.of(JOHAN, ShareKind.Tasks))
    }

    @Test
    fun aDaylightSavingNightCountsOnTheRightDay() {
        // Summer time ends at 03:00 on Sunday 25 October 2026. 23:30 UTC that Sunday is 00:30 on Monday in Stockholm.
        val d = compute(
            listOf(FamilyTask("a", "Uppgift", done = true, doneBy = SARA, doneAt = Instant.parse("2026-10-25T23:30:00Z"))),
            today = LocalDate(2026, 10, 25),
        )
        assertEquals(0, d.of(SARA, ShareKind.Tasks))
        val monday = compute(
            listOf(FamilyTask("a", "Uppgift", done = true, doneBy = SARA, doneAt = Instant.parse("2026-10-25T23:30:00Z"))),
            today = LocalDate(2026, 10, 26),
        )
        assertEquals(IsoWeek(2026, 44), monday.toWeek)
        assertEquals(1, monday.of(SARA, ShareKind.Tasks))
    }

    @Test
    fun recurringTasksCountEachTickedOccurrence() {
        val reading = FamilyTask(
            "t-lasning",
            "Läsa 20 minuter",
            rrule = "FREQ=DAILY",
            completions = mapOf(
                "2026-10-18" to Completion(SARA, null),
                "2026-10-19" to Completion(SARA, Instant.parse("2026-10-19T17:00:00Z")),
                "2026-10-20" to Completion(JOHAN, null),
                // Before the period.
                "2026-09-27" to Completion(SARA, null),
                // Somebody's tick that did not say who.
                "2026-10-17" to Completion(null, null),
            ),
        )
        val d = compute(listOf(reading))
        assertEquals(2, d.of(SARA, ShareKind.Tasks))
        assertEquals(1, d.of(JOHAN, ShareKind.Tasks))
        assertEquals(0, d.of(SARA, ShareKind.Chores))
    }

    @Test
    fun choresCountWhoDidThemNotWhoseTurnItWas() {
        val vacuum = FamilyTask(
            "t-dammsug",
            "Dammsuga",
            kind = TaskKind.Chore,
            rrule = "FREQ=WEEKLY;BYDAY=SA",
            rotation = ChoreRotation(listOf(SARA, JOHAN), 1, IsoWeek(2026, 40)),
            completions = mapOf(
                // Week 40 was Sara's turn, Johan did it.
                "2026-10-03" to Completion(JOHAN, null),
                "2026-10-10" to Completion(JOHAN, null),
                "2026-10-17" to Completion(SARA, null),
            ),
        )
        val d = compute(listOf(vacuum))
        assertEquals(2, d.of(JOHAN, ShareKind.Chores))
        assertEquals(1, d.of(SARA, ShareKind.Chores))
        assertEquals(0, d.of(JOHAN, ShareKind.Tasks))
    }

    @Test
    fun handoffsCountAsPlannedForTheFamilysOccurrencesUpToToday() {
        val school = Event(
            "e-school",
            "Skola",
            LocalDateTime(LocalDate(2026, 10, 12), LocalTime(8, 15)),
            LocalDateTime(LocalDate(2026, 10, 12), LocalTime(15, 0)),
            rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            participantIds = listOf(ELLA),
            dropOff = Handoff(JOHAN),
            pickUp = Handoff(SARA),
        )
        // Monday 12 – Tuesday 20 October: 7 school days, each with a drop-off and a pick-up.
        val d = compute(events = listOf(school))
        assertEquals(7, d.of(JOHAN, ShareKind.Handoffs))
        assertEquals(7, d.of(SARA, ShareKind.Handoffs))
        // Nobody chosen yet is nobody's.
        val open = school.copy(dropOff = Handoff(null))
        assertEquals(0, compute(events = listOf(open)).of(JOHAN, ShareKind.Handoffs))
        // A subscription's events are not the family's hand-offs.
        val subscribed = EventExpander.expand(listOf(school), tuesday, tuesday, zone, EventSource.Subscription)
        val none = TaskDistribution.compute(tuesday, zone, people, emptyList(), subscribed, emptyList())
        assertTrue(none.isEmpty)
    }

    @Test
    fun acceptedRequestsCountForWhoSaidYes() {
        val request = { id: String, date: LocalDate, status: RequestStatus, by: String? ->
            HandoffRequest(id, "e-school", date, HandoffKind.PickUp, fromMemberId = SARA, status = status, respondedBy = by)
        }
        val d = compute(
            requests = listOf(
                request("r1", LocalDate(2026, 10, 16), RequestStatus.Accepted, BENGT),
                request("r2", LocalDate(2026, 10, 9), RequestStatus.Accepted, BENGT),
                request("r3", LocalDate(2026, 10, 15), RequestStatus.Declined, JOHAN),
                request("r4", LocalDate(2026, 10, 15), RequestStatus.Pending, null),
                // Next week's yes has not happened yet.
                request("r5", LocalDate(2026, 10, 23), RequestStatus.Accepted, JOHAN),
            ),
        )
        assertEquals(2, d.of(BENGT, ShareKind.Requests))
        assertEquals(0, d.of(JOHAN, ShareKind.Requests))
        // Parents always have a row; Bengt only where he did something.
        assertEquals(listOf(SARA, JOHAN, BENGT), d.rows(ShareKind.Requests).map { it.person.id })
        assertEquals(emptyList(), d.rows(ShareKind.Chores))
    }

    @Test
    fun someoneWhoLeftIsCountedForNobodyElse() {
        val d = compute(
            listOf(
                FamilyTask("a", "Uppgift", done = true, doneBy = "m-old", doneAt = Instant.parse("2026-10-14T10:00:00Z")),
                FamilyTask("b", "Uppgift", done = true, doneBy = JOHAN, doneAt = Instant.parse("2026-10-14T10:00:00Z")),
                // A child's id would only come from odd data: not counted at all.
                FamilyTask("c", "Uppgift", done = true, doneBy = ELLA, doneAt = Instant.parse("2026-10-14T10:00:00Z")),
            ),
        )
        assertEquals(listOf(0, 1, 0), d.shares.map { it.count(ShareKind.Tasks) })
        assertEquals(1, d.formerCount())
        assertEquals(listOf(SARA, JOHAN), d.rows(ShareKind.Tasks).map { it.person.id })
    }

    private fun LocalDate.minusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() - n)

    private fun LocalDate.plusDaysBy(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
    }
}

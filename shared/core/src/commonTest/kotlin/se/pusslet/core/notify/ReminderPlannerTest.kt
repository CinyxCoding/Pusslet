package se.pusslet.core.notify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Completion
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.notify.NotifyFixtures.bengt
import se.pusslet.core.notify.NotifyFixtures.ella
import se.pusslet.core.notify.NotifyFixtures.event
import se.pusslet.core.notify.NotifyFixtures.gymBag
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.johan
import se.pusslet.core.notify.NotifyFixtures.preschool
import se.pusslet.core.notify.NotifyFixtures.sara
import se.pusslet.core.notify.NotifyFixtures.school
import se.pusslet.core.notify.NotifyFixtures.simon
import se.pusslet.core.notify.NotifyFixtures.wednesday
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.TimeWindow
import se.pusslet.core.today.Greeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReminderPlannerTest {
    private val thursday = LocalDate(2026, 10, 22)

    private fun plan(input: ReminderInput) = ReminderPlanner.plan(input)

    private fun List<PlannedNotification>.of(kind: NotificationKind) = filter { it.kind == kind }

    private fun List<PlannedNotification>.at(key: String): PlannedNotification = single { it.key == key }

    private val dentist = event(
        "e-dent",
        "Tandläkare",
        wednesday,
        LocalTime(14, 30),
        LocalTime(15, 0),
        participants = listOf(simon.id),
        reminders = listOf(30),
        location = "Folktandvården",
    )

    // --- Event reminders ---

    @Test
    fun eventReminderComesTheChosenMinutesBefore() {
        val n = plan(input(events = listOf(dentist))).at("event:e-dent@2026-10-21:30")
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), n.at) // 14:00 in Stockholm
        assertEquals(NotificationKind.Reminder, n.kind)
        assertTrue(n.userTimed)
        val p = assertIs<NotificationPayload.EventReminder>(n.payload)
        assertEquals(listOf("Simon"), p.children)
        assertEquals("Folktandvården", p.location)
        assertEquals(NotificationTarget.Occurrence("e-dent", wednesday), n.target)
    }

    @Test
    fun everyChosenReminderIsItsOwnNotification() {
        val twice = dentist.copy(reminders = listOf(60, 30, 30))
        val keys = plan(input(events = listOf(twice))).map { it.key }
        assertEquals(listOf("event:e-dent@2026-10-21:60", "event:e-dent@2026-10-21:30"), keys)
    }

    @Test
    fun anotherAdultsOwnEventStaysQuiet() {
        val meeting = event("e-meet", "Möte", wednesday, LocalTime(13, 0), LocalTime(14, 0), listOf(johan.id), listOf(10))
        assertTrue(plan(input(events = listOf(meeting))).isEmpty())
        // Johan himself is reminded.
        assertEquals(1, plan(input(me = johan.id, events = listOf(meeting))).size)
    }

    @Test
    fun aFamilyEventWithoutAdultsRemindsParentsButNotCaregivers() {
        val dinner = event("e-dinner", "Middag hos farmor", wednesday, LocalTime(17, 0), LocalTime(19, 0), reminders = listOf(60))
        assertEquals(1, plan(input(events = listOf(dinner))).size)
        assertTrue(plan(input(me = bengt.id, role = Role.Caregiver, events = listOf(dinner))).isEmpty())
        // Simon's dentist is a child's event: a parent is reminded, morfar is not.
        assertTrue(plan(input(me = bengt.id, role = Role.Caregiver, events = listOf(dentist))).isEmpty())
    }

    @Test
    fun aCaregiverWithTheHandoffIsReminded() {
        val football = event("e-fb", "Fotboll", wednesday, LocalTime(17, 0), LocalTime(18, 0), listOf(ella.id), listOf(30))
            .copy(pickUp = Handoff(bengt.id, LocalTime(18, 0)))
        val keys = plan(input(me = bengt.id, role = Role.Caregiver, events = listOf(football))).map { it.key }
        assertTrue("event:e-fb@2026-10-21:30" in keys)
    }

    @Test
    fun allDayEventsAreRemindedOfAt18TheEveningBefore() {
        val studyDay =
            event("e-study", "Studiedag", thursday, LocalTime(0, 0), LocalTime(0, 0), listOf(ella.id), listOf(10, 60), allDay = true)
        val n = plan(input(events = listOf(studyDay))).single()
        assertEquals("event:e-study@2026-10-22:allday", n.key)
        assertEquals(Instant.parse("2026-10-21T16:00:00Z"), n.at) // Wednesday 18:00
        assertEquals(false, n.userTimed)
        assertTrue(assertIs<NotificationPayload.EventReminder>(n.payload).allDay)
    }

    @Test
    fun theDayBeforeIsTheSameClockTimeAcrossTheAutumnChange() {
        // Summer time ends on Sunday 25 October 2026 at 03:00; 08:00 on Sunday is 07:00Z, on Saturday 06:00Z.
        val sunday = LocalDate(2026, 10, 25)
        val match = event("e-match", "Match", sunday, LocalTime(8, 0), LocalTime(10, 0), listOf(ella.id), listOf(1440, 60))
        val list = plan(input(now = Instant.parse("2026-10-23T12:00:00Z"), events = listOf(match)))
        assertEquals(Instant.parse("2026-10-24T06:00:00Z"), list.at("event:e-match@2026-10-25:1440").at)
        assertEquals(Instant.parse("2026-10-25T06:00:00Z"), list.at("event:e-match@2026-10-25:60").at)
    }

    @Test
    fun remindersAndMorningAcrossTheSpringChange() {
        // Summer time starts on Sunday 29 March 2026 at 02:00 (to 03:00).
        val sunday = LocalDate(2026, 3, 29)
        val early = event("e-early", "Flyg", sunday, LocalTime(3, 30), LocalTime(5, 0), listOf(ella.id), listOf(60))
        val list = plan(
            input(
                now = Instant.parse("2026-03-27T23:00:00Z"),
                events = listOf(early),
                morningAt = LocalTime(6, 45),
            ),
        )
        // 03:30 CEST is 01:30Z; an hour before is 00:30Z (01:30 CET).
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), list.at("event:e-early@2026-03-29:60").at)
        assertEquals(Instant.parse("2026-03-28T05:45:00Z"), list.at("morning:2026-03-28").at)
        assertEquals(Instant.parse("2026-03-29T04:45:00Z"), list.at("morning:2026-03-29").at)
    }

    @Test
    fun aReminderBeforeMidnightBelongsToTheEveningBefore() {
        val night = event("e-night", "Nattåg", thursday, LocalTime(0, 15), LocalTime(6, 0), listOf(ella.id), listOf(30))
        assertEquals(Instant.parse("2026-10-21T21:45:00Z"), plan(input(events = listOf(night))).single().at) // Wed 23:45
    }

    @Test
    fun subscriptionAndDeviceEventsAreLeftOut() {
        val input = input(events = listOf(dentist))
        for (source in listOf(EventSource.Subscription, EventSource.Device)) {
            val other = input.copy(base = input.base.copy(occurrences = input.base.occurrences.map { it.copy(source = source) }))
            assertTrue(plan(other).none { it.key.startsWith("event:") })
        }
    }

    @Test
    fun passedTimesAreLeftOut() {
        val list = plan(input(now = Instant.parse("2026-10-21T12:00:00Z"), events = listOf(dentist)))
        // 14:00 in Stockholm is exactly now: passed.
        assertTrue(list.none { it.key.startsWith("event:") })
    }

    @Test
    fun onlyTheHorizonIsPlanned() {
        val later = event("e-later", "Tandläkare", LocalDate(2026, 10, 23), LocalTime(9, 0), LocalTime(9, 30), listOf(simon.id), listOf(30))
        // Friday 08:30 is after Wednesday 08:00 + 48 h.
        assertTrue(plan(input(events = listOf(later))).isEmpty())
    }

    // --- Hand-offs ---

    @Test
    fun myPickUpsComeHalfAnHourBefore() {
        val list = plan(input()).of(NotificationKind.HandoffSoon)
        assertEquals(
            listOf(
                "handoff:e-pre@2026-10-21:PickUp" to Instant.parse("2026-10-21T13:00:00Z"),
                "handoff:e-school@2026-10-21:PickUp" to Instant.parse("2026-10-21T13:15:00Z"),
                "handoff:e-pre@2026-10-22:PickUp" to Instant.parse("2026-10-22T13:00:00Z"),
                "handoff:e-school@2026-10-22:PickUp" to Instant.parse("2026-10-22T13:15:00Z"),
            ),
            list.map { it.key to it.at },
        )
        val p = assertIs<NotificationPayload.Handoff>(list[1].payload)
        assertEquals(HandoffKind.PickUp, p.kind)
        assertEquals(LocalTime(15, 45), p.time)
        assertEquals(listOf("Ella"), p.children)
        // A school block without a place: the child's school.
        assertEquals("Björkängsskolan", p.place)
        assertEquals(30.minutes, ReminderPlanner.HANDOFF_LEAD)
    }

    @Test
    fun theEventsPlaceWinsOverTheSchool() {
        val withPlace = school.copy(location = "Björkängsskolan, entré B")
        val p = plan(input(events = listOf(withPlace))).of(NotificationKind.HandoffSoon).first().payload
        assertEquals("Björkängsskolan, entré B", assertIs<NotificationPayload.Handoff>(p).place)
    }

    @Test
    fun johansDropOffsAreHisUpToTheEndOfTheHorizon() {
        val list = plan(input(me = johan.id)).of(NotificationKind.HandoffSoon)
        // Wednesday 07:20 has passed; Friday 07:20 (05:20Z) is just inside 48 h.
        assertEquals(listOf("handoff:e-pre@2026-10-22:DropOff", "handoff:e-pre@2026-10-23:DropOff"), list.map { it.key })
    }

    @Test
    fun anAcceptedAnswerCountsBeforeItIsWrittenIn() {
        val answer = HandoffRequest(
            id = "r1",
            eventId = school.id,
            occurrenceDate = thursday,
            kind = HandoffKind.PickUp,
            fromMemberId = sara.id,
            toMemberId = bengt.id,
            status = RequestStatus.Accepted,
            respondedBy = bengt.id,
            respondedAt = Instant.parse("2026-10-20T18:00:00Z"),
        )
        val bengts = plan(input(me = bengt.id, role = Role.Caregiver, requests = listOf(answer)))
        assertEquals(listOf("handoff:e-school@2026-10-22:PickUp"), bengts.of(NotificationKind.HandoffSoon).map { it.key })
        assertEquals(
            listOf("pickup-check:e-school@2026-10-22/PickUp"),
            bengts.of(NotificationKind.Pickups).map {
                it.key
            },
            "his to tick off",
        )
        val saras = plan(input(requests = listOf(answer))).of(NotificationKind.HandoffSoon).map { it.key }
        assertTrue("handoff:e-school@2026-10-22:PickUp" !in saras)
        assertTrue("handoff:e-school@2026-10-21:PickUp" in saras)
        // Once written in (appliedAt), the event decides again.
        val applied = answer.copy(appliedAt = Instant.parse("2026-10-20T19:00:00Z"))
        assertTrue(plan(input(me = bengt.id, role = Role.Caregiver, requests = listOf(applied))).isEmpty())
    }

    // --- To-dos ---

    private fun task(id: String, assignee: String?, due: LocalDate?, time: LocalTime? = null) =
        FamilyTask(id = id, title = "Köp present", assigneeId = assignee, due = due, dueTime = time)

    @Test
    fun myToDosComeAtTheirTimeOrNine() {
        val list = plan(
            input(
                tasks = listOf(
                    task("t-mine", sara.id, wednesday, LocalTime(18, 0)),
                    task("t-nobody", null, thursday),
                    task("t-johan", johan.id, wednesday),
                    task("t-done", sara.id, wednesday).copy(done = true),
                    task("t-undated", sara.id, null),
                    task("t-late", sara.id, LocalDate(2026, 10, 20)),
                ),
            ),
        ).of(NotificationKind.Reminder)
        assertEquals(
            listOf(
                "task:t-mine:2026-10-21" to Instant.parse("2026-10-21T16:00:00Z"),
                "task:t-nobody:2026-10-22" to Instant.parse("2026-10-22T07:00:00Z"),
            ),
            list.map { it.key to it.at },
        )
        assertTrue(list[0].userTimed)
        assertEquals(false, list[1].userTimed)
        assertEquals(NotificationTarget.Task("t-mine"), list[0].target)
    }

    @Test
    fun aCaregiverIsNotRemindedOfToDosNobodyHas() {
        val shared = task("t-nobody", null, thursday).copy(sharedWithCaregivers = true)
        assertTrue(plan(input(me = bengt.id, role = Role.Caregiver, tasks = listOf(shared))).isEmpty())
    }

    @Test
    fun aRecurringToDoOncePerOpenOccurrence() {
        val daily = FamilyTask(
            id = "t-daily",
            title = "Läsläxa",
            assigneeId = sara.id,
            due = LocalDate(2026, 10, 1),
            rrule = "FREQ=DAILY",
            completions = mapOf("2026-10-22" to Completion(sara.id, null)),
        )
        // Wednesday 09:00 is ahead, Thursday is done, Friday 09:00 is after the horizon.
        assertEquals(listOf("task:t-daily:2026-10-21"), plan(input(events = emptyList(), tasks = listOf(daily))).map { it.key })
    }

    @Test
    fun aRotatingChoreAcrossWeek53() {
        // 2026 has 53 ISO weeks: W53 is 28 December – 3 January, then 2027-W01.
        val chore = FamilyTask(
            id = "t-rot",
            title = "Dammsuga",
            due = LocalDate(2026, 12, 28),
            rrule = "FREQ=DAILY",
            rotation = ChoreRotation(listOf(sara.id, johan.id), anchorWeek = IsoWeek(2026, 53)),
        )
        val now = Instant.parse("2027-01-02T12:00:00Z")
        assertEquals(listOf("task:t-rot:2027-01-03"), plan(input(now = now, events = emptyList(), tasks = listOf(chore))).map { it.key })
        assertEquals(
            listOf("task:t-rot:2027-01-04"),
            plan(input(now = now, me = johan.id, events = emptyList(), tasks = listOf(chore))).map { it.key },
        )
    }

    // --- Morgonkollen and Kvällskollen ---

    @Test
    fun theMorningBriefTellsTheDay() {
        val early = Instant.parse("2026-10-21T03:00:00Z") // 05:00
        val list = plan(input(now = early, rules = listOf(gymBag), morningAt = LocalTime(6, 45))).of(NotificationKind.Morning)
        assertEquals(listOf("morning:2026-10-21", "morning:2026-10-22"), list.map { it.key })
        assertEquals(Instant.parse("2026-10-21T04:45:00Z"), list[0].at)
        assertTrue(list[0].userTimed)
        val wed = assertIs<NotificationPayload.Morning>(list[0].payload)
        assertEquals(Greeting.Morning, wed.greeting)
        assertEquals(
            listOf(
                BriefHandoff(HandoffKind.DropOff, "Johan", isMe = false, listOf(BriefStop("Simon", LocalTime(7, 50)))),
                BriefHandoff(
                    HandoffKind.PickUp,
                    "Sara",
                    isMe = true,
                    listOf(BriefStop("Simon", LocalTime(15, 30)), BriefStop("Ella", LocalTime(15, 45))),
                ),
            ),
            wed.handoffs,
        )
        assertTrue(wed.packing.isEmpty())
        // The gym bag is for Thursday.
        assertEquals(listOf(BriefPacking("Ella", listOf("Gympapåse"))), assertIs<NotificationPayload.Morning>(list[1].payload).packing)
        assertEquals(NotificationTarget.Today, list[0].target)
    }

    @Test
    fun theMorningBriefOnlyWhenOnAndAhead() {
        assertTrue(plan(input()).of(NotificationKind.Morning).isEmpty())
        // 08:00 now: today's 06:45 has passed, so tomorrow and Friday's are planned.
        val keys = plan(input(morningAt = LocalTime(6, 45))).of(NotificationKind.Morning).map { it.key }
        assertEquals(listOf("morning:2026-10-22", "morning:2026-10-23"), keys)
    }

    @Test
    fun aQuietMorningNamesTheFirstEvent() {
        val saturday = Instant.parse("2026-10-24T04:00:00Z")
        val swim = event("e-swim", "Simskola", LocalDate(2026, 10, 24), LocalTime(10, 0), LocalTime(11, 0), listOf(ella.id))
        val p = plan(input(now = saturday, events = listOf(swim), morningAt = LocalTime(7, 0))).first().payload
        val morning = assertIs<NotificationPayload.Morning>(p)
        assertTrue(morning.handoffs.isEmpty())
        assertEquals(BriefEvent("Simskola", LocalTime(10, 0)), morning.first)
    }

    @Test
    fun kvallskollWhenTomorrowHasSomethingToPack() {
        val n = plan(input(rules = listOf(gymBag))).of(NotificationKind.Evening).single()
        assertEquals("evening:2026-10-21", n.key)
        assertEquals(Instant.parse("2026-10-21T17:00:00Z"), n.at) // 19:00
        assertEquals(NotificationPayload.Evening(thursday, listOf(BriefPacking("Ella", listOf("Gympapåse")))), n.payload)
    }

    @Test
    fun noKvallskollWhenPackedOrForCaregivers() {
        val packed = mapOf(PackingChecks.docId(thursday, ella.id) to PackingChecks(thursday, ella.id, mapOf("rule:p-gym" to true)))
        assertTrue(plan(input(rules = listOf(gymBag), checks = packed)).of(NotificationKind.Evening).isEmpty())
        assertTrue(plan(input(me = bengt.id, role = Role.Caregiver, rules = listOf(gymBag))).of(NotificationKind.Evening).isEmpty())
    }

    // --- Keys and the whole schedule ---

    @Test
    fun keysAreUniqueAndStableAcrossRuns() {
        val events = listOf(school, preschool, dentist)
        val first = plan(input(events = events, rules = listOf(gymBag), morningAt = LocalTime(6, 45)))
        val again = plan(input(now = NotifyFixtures.now + 1.minutes, events = events, rules = listOf(gymBag), morningAt = LocalTime(6, 45)))
        assertEquals(first.size, first.map { it.key }.toSet().size)
        assertEquals(first.map { it.key to it.at }, again.map { it.key to it.at })
        assertEquals(first.map { it.notificationId }, again.map { it.notificationId })
    }

    @Test
    fun withoutAMemberNothingIsPlanned() {
        assertTrue(plan(input(me = null, role = null, morningAt = LocalTime(6, 45))).isEmpty())
    }

    @Test
    fun scheduleDefersAndBundlesToDosInQuietHours() {
        val quiet = QuietSettings(TimeWindow(LocalTime(21, 0), LocalTime(9, 30)), enabled = true)
        val tasks = listOf(task("t-a", sara.id, thursday), task("t-b", null, thursday), task("t-c", sara.id, thursday, LocalTime(7, 0)))
        val list = ReminderPlanner.schedule(input(events = emptyList(), tasks = tasks, quiet = quiet))
        // 07:00 was typed by the user and stays; the two 09:00 ones wait until 09:30 and come as one.
        assertEquals(listOf("task:t-c:2026-10-22", "bundle:Reminder:task:t-a:2026-10-22"), list.map { it.key })
        assertEquals(Instant.parse("2026-10-22T07:30:00Z"), list[1].at)
        assertEquals(2, assertIs<NotificationPayload.Bundle>(list[1].payload).items.size)
        assertNull(ReminderPlanner.schedule(input(events = emptyList())).firstOrNull())
    }

    @Test
    fun aDeferredToDoIsStillPlannedAfterItsOwnTime() {
        // The worker plans again at 09:10, after the to-do's own 09:00: it still comes at 09:30.
        val quiet = QuietSettings(TimeWindow(LocalTime(21, 0), LocalTime(9, 30)), enabled = true)
        val tasks = listOf(task("t-a", sara.id, wednesday))
        val list = ReminderPlanner.schedule(
            input(now = Instant.parse("2026-10-21T07:10:00Z"), events = emptyList(), tasks = tasks, quiet = quiet),
        )
        assertEquals(listOf("task:t-a:2026-10-21" to Instant.parse("2026-10-21T07:30:00Z")), list.map { it.key to it.at })
    }

    @Test
    fun aSummaryThatWasShownKeepsItsMembersAway() {
        val tasks = listOf(task("t-a", sara.id, wednesday, LocalTime(9, 0)), task("t-b", sara.id, wednesday, LocalTime(9, 3)))
        val before = ReminderPlanner.schedule(input(now = Instant.parse("2026-10-21T06:59:00Z"), events = emptyList(), tasks = tasks))
        assertEquals(listOf("bundle:Reminder:task:t-a:2026-10-21"), before.map { it.key })
        // At 09:01 the summary has been shown; 09:03 must not come again on its own.
        assertTrue(
            ReminderPlanner.schedule(input(now = Instant.parse("2026-10-21T07:01:00Z"), events = emptyList(), tasks = tasks)).isEmpty(),
        )
    }
}

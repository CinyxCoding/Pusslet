package se.pusslet.data.notify

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakePreferenceDao
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.MorningBrief
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.repository.TimeWindow
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sources.LunchMenuProvider
import se.pusslet.data.sources.Sourced
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.TodayInputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The notification plan from real repositories over the in-memory store, on Sara's and morfar Bengt's phones. */
class NotificationPlanSourceTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val wednesday = LocalDate(2026, 10, 21)
    private val thursday = LocalDate(2026, 10, 22)
    private var time = Instant.parse("2026-10-21T03:00:00Z") // Wednesday 05:00
    private val clock = object : Clock {
        override fun now() = time
    }
    private val store = InMemorySyncLocalStore()

    private object NoLunchFeeds : LunchMenuProvider {
        override fun supports(source: LunchSource) = false

        override suspend fun lunches(source: LunchSource, force: Boolean): Sourced<List<SchoolLunch>>? = null
    }

    private inner class Phone(memberId: String?, role: Role = Role.Parent) {
        val sessions = FixedSessionProvider(
            if (memberId == null) Session() else Session(familyId = "f", memberId = memberId, uid = "u-$memberId", role = role),
        )
        val docs = DocumentStore(store, sessions, clock)
        val prefs = PreferencesRepository(FakePreferenceDao())
        val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), SequentialIdGenerator(), clock)
        val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
        val packing = PackingRepository(docs)
        val tasks = TaskRepository(docs)
        val today =
            TodayInputSource(
                family,
                calendar,
                packing,
                MealRepository(docs),
                tasks,
                LunchRepository(docs, NoLunchFeeds, family),
                sessions,
                clock,
                zone,
            )
        val plan = NotificationPlanSource(today, prefs, sessions, clock, zone)
    }

    private val sara = Phone("m-sara")
    private val bengt = Phone("m-bengt", Role.Caregiver)

    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(LocalDate(2026, 10, 19), LocalTime(8, 15)),
        end = LocalDateTime(LocalDate(2026, 10, 19), LocalTime(14, 30)),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        tags = listOf("skola"),
        participantIds = listOf("c-ella"),
        pickUp = Handoff("m-sara", LocalTime(15, 45)),
    )

    private val dentist = Event(
        id = "e-dent",
        title = "Tandläkare",
        start = LocalDateTime(wednesday, LocalTime(14, 30)),
        end = LocalDateTime(wednesday, LocalTime(15, 0)),
        participantIds = listOf("c-ella"),
        reminders = listOf(30),
    )

    private suspend fun family() {
        sara.family.saveMember(Member("m-sara", "Sara", Role.Parent))
        sara.family.saveMember(Member("m-johan", "Johan", Role.Parent))
        sara.family.saveMember(Member("m-bengt", "Bengt", Role.Caregiver))
        sara.family.saveChild(Child("c-ella", "Ella", school = SchoolRef("Björkängsskolan")))
        sara.calendar.saveEvent(school)
        sara.calendar.saveEvent(dentist)
    }

    @Test
    fun theWholePlanFromTheRepositories() = runTest {
        family()
        sara.tasks.save(
            FamilyTask(id = "t-gift", title = "Köp present", assigneeId = "m-sara", due = wednesday, dueTime = LocalTime(18, 0)),
        )
        sara.prefs.set(Prefs.Morning, MorningBrief(enabled = true, time = LocalTime(6, 45)))
        val list = sara.plan.planNow()
        assertEquals(
            listOf(
                "morning:2026-10-21",
                "event:e-dent@2026-10-21:30",
                "handoff:e-school@2026-10-21:PickUp",
                "task:t-gift:2026-10-21",
                "morning:2026-10-22",
                "handoff:e-school@2026-10-22:PickUp",
            ),
            list.map { it.key },
        )
        val handoff = assertIs<NotificationPayload.Handoff>(list[2].payload)
        assertEquals("Björkängsskolan", handoff.place)
        val morning = assertIs<NotificationPayload.Morning>(list[0].payload)
        assertEquals(listOf("Ella"), morning.handoffs.single().stops.map { it.child })
    }

    @Test
    fun nothingWithoutAFamilyOnThePhone() = runTest {
        family()
        assertTrue(Phone(null).plan.planNow().isEmpty())
    }

    @Test
    fun aReminderDoesNotDependOnKalendersFilter() = runTest {
        family()
        sara.prefs.set(Prefs.CalendarLayers, emptySet())
        assertTrue(sara.plan.planNow().any { it.key == "event:e-dent@2026-10-21:30" })
    }

    @Test
    fun bengtsYesMakesItHisAtOnce() = runTest {
        family()
        sara.calendar.saveHandoffRequest(
            HandoffRequest(
                id = "r1",
                eventId = school.id,
                occurrenceDate = thursday,
                kind = HandoffKind.PickUp,
                fromMemberId = "m-sara",
                toMemberId = "m-bengt",
                status = RequestStatus.Accepted,
                respondedBy = "m-bengt",
                respondedAt = time,
            ),
        )
        assertEquals(listOf("handoff:e-school@2026-10-22:PickUp"), bengt.plan.planNow().map { it.key })
        assertTrue(sara.plan.planNow().none { it.key == "handoff:e-school@2026-10-22:PickUp" })
    }

    @Test
    fun quietHoursFromThePreferences() = runTest {
        family()
        sara.packing.saveRule(PackingRule("p-gym", "Gympapåse", "c-ella", PackingTrigger.Weekly(setOf(DayOfWeek.THURSDAY))))
        // Kvällskollen at 19:00 is inside 18:30–06:30 and is dropped; without quiet hours it comes.
        sara.prefs.set(Prefs.QuietHours, TimeWindow(LocalTime(18, 30), LocalTime(6, 30)))
        assertTrue(sara.plan.planNow().none { it.kind == NotificationKind.Evening })
        sara.prefs.set(Prefs.QuietHoursEnabled, false)
        assertEquals(listOf("evening:2026-10-21"), sara.plan.planNow().filter { it.kind == NotificationKind.Evening }.map { it.key })
    }

    @Test
    fun aWorkerFindsItsNotificationAtItsTime() = runTest {
        family()
        val key = "event:e-dent@2026-10-21:30"
        time = Instant.parse("2026-10-21T12:01:00Z") // a minute late
        val due = assertNotNull(sara.plan.due(key))
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"), due.at)
        // Too early, or long after: not now.
        time = Instant.parse("2026-10-21T10:00:00Z")
        assertNull(sara.plan.due(key))
        time = Instant.parse("2026-10-21T12:00:00Z") + 30.minutes
        assertNull(sara.plan.due(key))
        // Removed since: nothing to show.
        time = Instant.parse("2026-10-21T12:01:00Z")
        sara.calendar.deleteEvent(dentist.id)
        assertNull(sara.plan.due(key))
    }

    @Test
    fun changesFollowTheData() = runTest {
        family()
        assertTrue(sara.plan.changes().first().none { it.key.startsWith("task:") })
        sara.tasks.save(FamilyTask(id = "t-1", title = "Köp present", assigneeId = "m-sara", due = thursday))
        assertTrue(sara.plan.changes().first().any { it.key == "task:t-1:2026-10-22" })
        assertTrue(Phone(null).plan.changes().first().isEmpty())
    }
}

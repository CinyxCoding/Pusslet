package se.pusslet.core.helper

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolKind
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import se.pusslet.core.weather.Forecast
import se.pusslet.core.weather.WeatherPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Morfar Bengt's view of a week like the demo's: Thursday 8 October 2026 (week 41) he picks up Simon 15:30 from Förskolan
 * Ekorren and Ella 15:45 from Björkängsskolan; on the other weekdays Sara picks up and Johan drops off.
 */
class HelperPlannerTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val thursday = LocalDate(2026, 10, 8)
    private val friday = LocalDate(2026, 10, 9)
    private val nextThursday = LocalDate(2026, 10, 15)
    private val monday = LocalDate(2026, 10, 5)

    private val people = listOf(
        FamilyPerson.of(Member(SARA, "Sara", Role.Parent)),
        FamilyPerson.of(Member(JOHAN, "Johan", Role.Parent)),
        FamilyPerson.of(Member(BENGT, "Bengt", Role.Caregiver)),
    )
    private val ella = Child(ELLA, "Ella", school = SchoolRef("Björkängsskolan"))
    private val simon = Child(SIMON, "Simon", school = SchoolRef("Förskolan Ekorren", kind = SchoolKind.Preschool))
    private val children = listOf(ella, simon)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    /** Simon's preschool: Monday–Wednesday and Friday Sara picks up, Thursdays Bengt at 15:30. */
    private val preschool = Event(
        "e-forskola",
        "Förskola",
        at(monday, 8),
        at(monday, 16),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,FR",
        participantIds = listOf(SIMON),
        dropOff = Handoff(JOHAN, LocalTime(7, 50)),
        pickUp = Handoff(SARA, LocalTime(16, 0)),
        tags = listOf("förskola"),
    )
    private val preschoolThursday = Event(
        "e-forskola-to",
        "Förskola",
        at(thursday, 8),
        at(thursday, 16),
        rrule = "FREQ=WEEKLY;BYDAY=TH",
        participantIds = listOf(SIMON),
        dropOff = Handoff(JOHAN, LocalTime(7, 50)),
        pickUp = Handoff(BENGT, LocalTime(15, 30)),
        tags = listOf("förskola"),
    )
    private val school = Event(
        "e-skola",
        "Skola & fritids",
        at(monday, 8, 15),
        at(monday, 16, 30),
        rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,FR",
        participantIds = listOf(ELLA),
        dropOff = Handoff(JOHAN, LocalTime(8, 5)),
        pickUp = Handoff(SARA, LocalTime(16, 30)),
        tags = listOf("skola"),
    )
    private val schoolThursday = Event(
        "e-skola-to",
        "Skola & fritids",
        at(thursday, 8, 15),
        at(thursday, 16),
        rrule = "FREQ=WEEKLY;BYDAY=TH",
        participantIds = listOf(ELLA),
        dropOff = Handoff(JOHAN, LocalTime(8, 5)),
        pickUp = Handoff(BENGT, LocalTime(15, 45)),
        tags = listOf("skola"),
    )
    private val dance = Event(
        "e-dans",
        "Dans",
        at(thursday, 17, 15),
        at(thursday, 18, 15),
        location = "Kulturskolan",
        participantIds = listOf(ELLA),
    )
    private val board = Event(
        "e-styrelse",
        "Styrelsemöte",
        at(thursday, 16),
        at(thursday, 17, 30),
        participantIds = listOf(SARA),
        sharedWithCaregivers = false,
    )
    private val week = listOf(preschool, preschoolThursday, school, schoolThursday)

    private fun expand(events: List<Event>, source: EventSource = EventSource.Family): List<EventOccurrence> =
        EventExpander.expand(events, thursday, thursday.plusDays(HelperPlanner.WEEK_DAYS), zone, source)

    private fun input(
        events: List<Event> = week,
        now: LocalTime = LocalTime(7, 10),
        today: LocalDate = thursday,
        extra: List<EventOccurrence> = emptyList(),
        requests: List<HandoffRequest> = emptyList(),
        rules: List<PackingRule> = emptyList(),
        checks: Map<String, PackingChecks> = emptyMap(),
        tasks: List<FamilyTask> = emptyList(),
        forecast: Forecast? = null,
    ) = HelperInput(
        today = today,
        now = now,
        zone = zone,
        me = BENGT,
        people = people,
        children = children,
        occurrences = expand(events) + extra,
        requests = requests,
        packingRules = rules,
        packingChecks = checks,
        tasks = tasks,
        forecast = forecast,
    )

    private fun plan(input: HelperInput = input()) = HelperPlanner.build(input)

    private fun HelperMission.summary() = "${kind.name} ${children.joinToString { it.name }} $time"

    private fun request(
        id: String,
        eventId: String = "e-skola",
        date: LocalDate = friday,
        to: String? = BENGT,
        from: String = SARA,
        status: RequestStatus = RequestStatus.Pending,
        respondedBy: String? = null,
        declines: Map<String, Instant> = emptyMap(),
        appliedAt: Instant? = null,
        createdAt: Instant = Instant.parse("2026-10-07T18:00:00Z"),
    ) = HandoffRequest(
        id = id,
        eventId = eventId,
        occurrenceDate = date,
        kind = HandoffKind.PickUp,
        fromMemberId = from,
        toMemberId = to,
        status = status,
        createdAt = createdAt,
        respondedBy = respondedBy,
        respondedAt = respondedBy?.let { Instant.parse("2026-10-07T19:00:00Z") },
        declines = declines,
        appliedAt = appliedAt,
    )

    @Test
    fun todayHasOnlyTheHandOffsBengtDoesHimself() {
        val plan = plan()

        assertEquals(listOf("PickUp Simon 15:30", "PickUp Ella 15:45"), plan.missions.map { it.summary() })
        // Johan's drop-offs that morning are not Bengt's.
        assertTrue(plan.missions.none { it.kind == HandoffKind.DropOff })
        assertEquals(listOf("Förskolan Ekorren", "Björkängsskolan"), plan.missions.map { it.place })
        assertEquals(listOf(null, null), plan.missions.map { it.title }, "School and preschool need no name")
        assertNull(plan.next, "Nästa gång only when nothing is left today")
    }

    @Test
    fun privateEventsAreLeftOutEvenWhenPassedIn() {
        val secret = board.copy(id = "e-secret", participantIds = listOf(SIMON), pickUp = Handoff(BENGT, LocalTime(17, 30)))
        val plan = plan(input(events = week + board + secret))

        assertTrue(plan.missions.none { it.occurrence.event.id == "e-secret" })
        val titles = plan.days.flatMap { it.items }.filterIsInstance<HelperWeekItem.Event>().map { it.occurrence.event.title }
        assertTrue("Styrelsemöte" !in titles)
    }

    @Test
    fun subscriptionsAndDeviceCalendarsAreLeftOut() {
        val club = Event(
            "e-klubb",
            "Klubbträning",
            at(friday, 17),
            at(friday, 18),
            participantIds = listOf(ELLA),
            pickUp = Handoff(BENGT, LocalTime(18, 0)),
        )
        val work = club.copy(id = "e-jobb", title = "Jobbmöte")
        val extra = expand(listOf(club), EventSource.Subscription) + expand(listOf(work), EventSource.Device)
        val plan = plan(input(extra = extra))

        assertTrue(
            plan.days.flatMap { it.items }.none { item ->
                item is HelperWeekItem.Event &&
                    item.occurrence.event.id in setOf("e-klubb", "e-jobb")
            },
        )
        assertTrue(plan.days.flatMap { it.items }.filterIsInstance<HelperWeekItem.Mission>().none { it.mission.date == friday })
    }

    @Test
    fun anAcceptedAnswerMakesBengtResponsibleBeforeTheEventIsUpdated() {
        val yes = request("r1", status = RequestStatus.Accepted, respondedBy = BENGT)
        val plan = plan(input(requests = listOf(yes)))

        val day = plan.upcoming.first()
        assertEquals(friday, day.date)
        assertEquals(listOf("PickUp Ella 16:30"), day.missions.map { it.summary() })
        // The card stays with "Du sa ja" and "Ändra svar" until a parent's phone writes it in.
        assertEquals(HelperAnswer.Yes, plan.requests.single().answer)
        assertTrue(plan.requests.single().canChangeAnswer)

        // Once applied, the event is the truth (here: still Sara, as if a parent changed it back afterwards).
        val applied = plan(input(requests = listOf(yes.copy(appliedAt = Instant.parse("2026-10-07T19:05:00Z")))))
        assertTrue(applied.upcoming.none { it.date == friday })
        assertTrue(applied.requests.isEmpty())
    }

    @Test
    fun requestsAreFilteredLikeTheRules() {
        val open = request("open")
        val toAnyone = request("anyone", eventId = "e-forskola", to = null)
        val toJohan = request("johan", eventId = "e-forskola", date = LocalDate(2026, 10, 12), to = JOHAN)
        val mine = request("mine", eventId = "e-forskola-to", date = thursday, from = BENGT, to = null)
        val declinedByMe = request(
            "declined",
            eventId = "e-skola",
            date = LocalDate(2026, 10, 12),
            to = null,
            declines = mapOf(
                BENGT to Instant.parse("2026-10-07T19:00:00Z"),
            ),
        )
        val passed = request("passed", eventId = "e-skola", date = LocalDate(2026, 10, 7))
        val takenByJohan =
            request(
                "taken",
                eventId = "e-skola",
                date = LocalDate(2026, 10, 13),
                to = null,
                status = RequestStatus.Accepted,
                respondedBy = JOHAN,
            )
        val cancelled = request("cancelled", eventId = "e-skola", date = LocalDate(2026, 10, 14), status = RequestStatus.Cancelled)
        val aboutPrivate = request("private", eventId = "e-styrelse", date = thursday)
        val plan = plan(
            input(
                events = week + board,
                requests = listOf(open, toAnyone, toJohan, mine, declinedByMe, passed, takenByJohan, cancelled, aboutPrivate),
            ),
        )

        assertEquals(listOf("anyone", "open", "declined"), plan.requests.map { it.request.id })
        assertEquals(listOf(null, null, HelperAnswer.No), plan.requests.map { it.answer })
        val first = plan.requests.first()
        assertEquals("Sara", first.from?.name)
        assertEquals(listOf("Simon"), first.children.map { it.name })
        assertEquals(LocalTime(16, 0), first.time)
        assertEquals("Förskolan Ekorren", first.place)
    }

    @Test
    fun aHandOffEarlierTodayIsPassedButOneLaterIsNot() {
        val today = request("today", eventId = "e-skola-to", date = thursday, to = null)

        assertEquals(1, plan(input(requests = listOf(today), now = LocalTime(15, 0))).requests.size)
        assertTrue(plan(input(requests = listOf(today), now = LocalTime(15, 46))).requests.isEmpty())
    }

    @Test
    fun theLatestRequestPerOccurrenceAndKindWins() {
        val first = request("first", declines = mapOf(BENGT to Instant.parse("2026-10-07T18:30:00Z")), to = null)
        val again = request("again", createdAt = Instant.parse("2026-10-07T20:00:00Z"))

        assertEquals(listOf("again"), plan(input(requests = listOf(first, again))).requests.map { it.request.id })
    }

    @Test
    fun bringForADropOffIsWhatIsNotPackedYet() {
        val dropOff = school.copy(dropOff = Handoff(BENGT, LocalTime(8, 5)))
        val rules = listOf("Gympapåse", "Bibliotekbok", "Regnkläder", "Vattenflaska", "Mössa").mapIndexed { i, item ->
            PackingRule("p$i", item, ELLA, PackingTrigger.Weekly(setOf(DayOfWeek.FRIDAY)))
        }
        val checks = mapOf(PackingChecks.docId(friday, ELLA) to PackingChecks(friday, ELLA, mapOf("rule:p4" to true)))
        val plan = plan(input(events = listOf(dropOff), today = friday, rules = rules, checks = checks))

        val mission = plan.missions.single { it.kind == HandoffKind.DropOff }
        assertEquals(listOf("Gympapåse", "Bibliotekbok", "Regnkläder"), mission.bring.map { it.text })
        assertEquals(1, mission.moreToBring, "Mössa is packed already")
        // He drops Ella off, so the whole list is his to tick.
        assertEquals(listOf("Ella"), plan.packing.map { it.child.name })
        assertEquals(5, plan.packing.single().items.size)
    }

    @Test
    fun bringAfterAPickUpIsOnlyForLaterEventsAndTheHandover() {
        val football = Event(
            "e-fotboll",
            "Fotboll",
            at(thursday, 17, 30),
            at(thursday, 18, 30),
            participantIds = listOf(SIMON),
            packingItems = listOf("Vattenflaska"),
        )
        val morning = Event(
            "e-utflykt",
            "Förmiddagsutflykt",
            at(thursday, 9),
            at(thursday, 11),
            participantIds = listOf(SIMON),
            packingItems = listOf("Sittunderlag"),
        )
        val plan = plan(input(events = week + football + morning))

        val simon = plan.missions.first { it.children.single().id == SIMON }
        assertEquals(listOf("Vattenflaska"), simon.bring.map { it.text })
        // Bengt does not drop anyone off, so there is no "Ska med" for him.
        assertTrue(plan.packing.isEmpty())
    }

    @Test
    fun afterIsTheChildsSharedEventsBeforeEight() {
        val choir = dance.copy(id = "e-kor", title = "Kör", start = at(thursday, 18, 30), end = at(thursday, 19, 15), location = "")
        val late = dance.copy(id = "e-sent", title = "Disco", start = at(thursday, 20, 15), end = at(thursday, 21, 0))
        val third = dance.copy(id = "e-tre", title = "Läxhjälp", start = at(thursday, 19, 30), end = at(thursday, 19, 45))
        val secret = dance.copy(id = "e-hemlig", title = "Hemlig", start = at(thursday, 16, 30), sharedWithCaregivers = false)
        val plan = plan(input(events = week + dance + choir + late + third + secret))

        val ella = plan.missions.first { it.children.single().id == ELLA }
        assertEquals(listOf("Dans", "Kör"), ella.after.map { it.occurrence.event.title })
        assertEquals("Ella", ella.after.first().child.name)
        assertTrue(plan.missions.first { it.children.single().id == SIMON }.after.isEmpty(), "Simon has nothing after")
    }

    @Test
    fun tasksAreSharedAndHisOrNobodysOnADayHeHasAMission() {
        val reading = FamilyTask("t-lasning", "Läsa 20 minuter", rrule = "FREQ=DAILY", sharedWithCaregivers = true)
        val his = FamilyTask("t-bengt", "Hämta paket", assigneeId = BENGT, due = friday, sharedWithCaregivers = true)
        val saras = FamilyTask("t-sara", "Ringa skolan", assigneeId = SARA, due = thursday, sharedWithCaregivers = true)
        val private = FamilyTask("t-privat", "Deklarera", due = thursday)
        val fridayNobody = FamilyTask("t-fre", "Köpa blommor", due = friday, sharedWithCaregivers = true)
        val tasks = listOf(reading, his, saras, private, fridayNobody)

        assertEquals(listOf("Läsa 20 minuter", "Hämta paket"), plan(input(tasks = tasks)).tasks.map { it.task.title })
        // Friday has no mission for him: nobody's tasks stay with the parents.
        assertEquals(listOf("Hämta paket"), plan(input(tasks = tasks, today = friday)).tasks.map { it.task.title })
    }

    @Test
    fun atMostThreeTasks() {
        val tasks = (1..5).map { FamilyTask("t$it", "Uppgift $it", assigneeId = BENGT, due = thursday, sharedWithCaregivers = true) }
        assertEquals(HelperPlanner.MAX_TASKS, plan(input(tasks = tasks)).tasks.size)
    }

    @Test
    fun soonFromAnHourBeforeAndDoneAnHourAfter() {
        assertEquals(MissionState.Upcoming, HelperPlanner.stateAt(LocalTime(15, 30), LocalTime(14, 29)))
        assertEquals(MissionState.Soon, HelperPlanner.stateAt(LocalTime(15, 30), LocalTime(14, 30)))
        assertEquals(MissionState.Soon, HelperPlanner.stateAt(LocalTime(15, 30), LocalTime(16, 29)))
        assertEquals(MissionState.Done, HelperPlanner.stateAt(LocalTime(15, 30), LocalTime(16, 30)))

        val afternoon = plan(input(now = LocalTime(16, 40)))
        assertEquals(listOf(MissionState.Done, MissionState.Soon), afternoon.missions.map { it.state })
        assertEquals(listOf("PickUp Ella 15:45"), afternoon.activeMissions.map { it.summary() })

        val evening = plan(input(now = LocalTime(17, 0)))
        assertTrue(evening.allDoneToday)
        assertEquals(nextThursday, evening.next?.date, "Nästa gång once today is done")
    }

    @Test
    fun nextTimeWhenThereIsNothingToday() {
        val plan = plan(input(today = friday))

        assertTrue(plan.missions.isEmpty())
        val next = assertNotNull(plan.next)
        assertEquals(nextThursday, next.date)
        assertEquals(listOf("PickUp Simon 15:30", "PickUp Ella 15:45"), next.missions.map { it.summary() })
        // One fact once: the day is not repeated under Kommande.
        assertTrue(plan.upcoming.none { it.date == nextThursday })
    }

    @Test
    fun nothingWithinTwoWeeksMeansNoNextTime() {
        assertNull(plan(input(events = listOf(preschool, school), today = friday)).next)
    }

    @Test
    fun upcomingShowsHisDaysWithTheWeekWhenItChanges() {
        val plan = plan()

        assertEquals(listOf(nextThursday), plan.upcoming.map { it.date })
        assertEquals(42, plan.upcoming.single().week)
        assertEquals(listOf("PickUp Simon 15:30", "PickUp Ella 15:45"), plan.upcoming.single().missions.map { it.summary() })
    }

    @Test
    fun theWholeWeekHasEverythingSharedButTheRoutines() {
        val plan = plan(input(events = week + dance + board))

        val first = plan.days.first()
        assertEquals(thursday, first.date)
        assertNull(first.week)
        assertEquals(
            listOf("mission 15:30", "mission 15:45", "Dans 17:15"),
            first.items.map { item ->
                when (item) {
                    is HelperWeekItem.Mission -> "mission ${item.mission.time}"
                    is HelperWeekItem.Event -> "${item.occurrence.event.title} ${item.time}"
                }
            },
        )
        // Days with only school, preschool and others' hand-offs are skipped; the week number shows when it changes.
        assertEquals(listOf(thursday, nextThursday), plan.days.map { it.date })
        assertEquals(42, plan.days[1].week)
    }

    @Test
    fun redDaysAreInTheWholeWeek() {
        // All Saints' Day, Saturday 31 October 2026.
        val plan = plan(input(events = listOf(preschool), today = LocalDate(2026, 10, 26)))
        assertTrue(plan.days.any { it.date == LocalDate(2026, 10, 31) && it.holidays.isNotEmpty() })
    }

    @Test
    fun thePreviewSeesWhatHisPhoneSees() {
        val club = Event("e-klubb", "Klubb", at(friday, 17), at(friday, 18), participantIds = listOf(ELLA), pickUp = Handoff(BENGT, null))
        val everything = input(
            events = week + dance + board,
            extra = expand(listOf(club), EventSource.Subscription),
            requests = listOf(request("r1"), request("r2", eventId = "e-styrelse", date = thursday)),
            tasks = listOf(
                FamilyTask("t1", "Läsa 20 minuter", rrule = "FREQ=DAILY", sharedWithCaregivers = true),
                FamilyTask("t2", "Deklarera", assigneeId = BENGT, due = thursday),
            ),
        )
        val synced = everything.copy(
            occurrences = expand(week + dance),
            requests = listOf(request("r1")),
            tasks = everything.tasks.filter { it.sharedWithCaregivers },
        )

        assertEquals(plan(synced), plan(everything))
    }

    @Test
    fun cannotMakeWaitsForAnAnswerAndThenSaysWhoTookIt() {
        val asked = request("r-kan-inte", eventId = "e-forskola-to", date = thursday, from = BENGT, to = null)
        val waiting = plan(input(requests = listOf(asked)))

        val simon = waiting.missions.first { it.children.single().id == SIMON }
        assertTrue(simon.waitingForTakeover)
        assertEquals(listOf("PickUp Ella 15:45"), waiting.cannotMakeCandidates.map { it.summary() })
        assertTrue(waiting.requests.isEmpty(), "His own request is not a question to him")

        val taken = plan(input(requests = listOf(asked.copy(status = RequestStatus.Accepted, respondedBy = JOHAN))))
        assertEquals(listOf("PickUp Ella 15:45"), taken.missions.map { it.summary() }, "Johan picks Simon up now")
        assertEquals("Johan", taken.takenOver.single().by?.name)
        assertEquals(listOf("Simon"), taken.takenOver.single().children.map { it.name })
    }

    @Test
    fun weatherOnlyForRainSnowOrCold() {
        val pickUp = LocalDateTime(thursday, LocalTime(15, 30)).toInstant(zone)

        fun forecast(temperature: Double, mm: Double) = Forecast(
            59.3,
            18.0,
            listOf(WeatherPoint(pickUp, temperature, precipitationMm = mm, precipitationType = 1)),
            fetchedAt = pickUp,
        )
        assertEquals(
            HelperWeather(HandoffKind.PickUp, HelperWeatherCondition.Rain, 9),
            plan(input(forecast = forecast(9.2, 0.8))).weather,
        )
        assertEquals(HelperWeatherCondition.Cold, plan(input(forecast = forecast(3.0, 0.0))).weather?.condition)
        assertNull(plan(input(forecast = forecast(12.0, 0.0))).weather)
        assertNull(plan(input(forecast = null)).weather)
    }

    private companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
        const val SIMON = "c-simon"
    }
}

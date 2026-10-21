package se.pusslet.core.notify

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.Member
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolKind
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.today.TodayInput
import kotlin.time.Instant

/** Familjen Lind: Sara (me) and Johan, morfar Bengt, Ella at Björkängsskolan and Simon at Ekorrens förskola. */
object NotifyFixtures {
    val zone = TimeZone.of("Europe/Stockholm")

    /** Wednesday 21 October 2026, 08:00 in Stockholm. */
    val wednesday = LocalDate(2026, 10, 21)
    val now: Instant = Instant.parse("2026-10-21T06:00:00Z")

    val sara = Member("m-sara", "Sara", Role.Parent)
    val johan = Member("m-johan", "Johan", Role.Parent)
    val bengt = Member("m-bengt", "Bengt", Role.Caregiver)
    val ella = Child("c-ella", "Ella", school = SchoolRef("Björkängsskolan"))
    val simon = Child("c-simon", "Simon", school = SchoolRef("Ekorrens förskola", kind = SchoolKind.Preschool))

    val people: List<FamilyPerson> = listOf(sara, johan, bengt).map(FamilyPerson::of) + listOf(ella, simon).map(FamilyPerson::of)

    private const val WEEKDAYS = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    private val monday = LocalDate(2026, 10, 19)

    /** Ella's school: Sara picks up 15:45. */
    val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(monday, LocalTime(8, 15)),
        end = LocalDateTime(monday, LocalTime(14, 30)),
        rrule = WEEKDAYS,
        tags = listOf("skola"),
        participantIds = listOf(ella.id),
        pickUp = Handoff(sara.id, LocalTime(15, 45)),
    )

    /** Simon's preschool: Johan drops off 07:50, Sara picks up 15:30. */
    val preschool = Event(
        id = "e-pre",
        title = "Förskola",
        start = LocalDateTime(monday, LocalTime(8, 0)),
        end = LocalDateTime(monday, LocalTime(16, 0)),
        rrule = WEEKDAYS,
        tags = listOf("förskola"),
        participantIds = listOf(simon.id),
        dropOff = Handoff(johan.id, LocalTime(7, 50)),
        pickUp = Handoff(sara.id, LocalTime(15, 30)),
    )

    fun event(
        id: String,
        title: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        participants: List<String> = emptyList(),
        reminders: List<Int> = emptyList(),
        location: String = "",
        allDay: Boolean = false,
    ) = Event(
        id = id,
        title = title,
        start = LocalDateTime(date, if (allDay) LocalTime(0, 0) else start),
        end = if (allDay) LocalDateTime(date.plusDays(1), LocalTime(0, 0)) else LocalDateTime(date, end),
        allDay = allDay,
        location = location,
        participantIds = participants,
        reminders = reminders,
    )

    val gymBag = PackingRule("p-gym", "Gympapåse", ella.id, PackingTrigger.Weekly(setOf(DayOfWeek.THURSDAY)))

    fun base(
        now: Instant = this.now,
        me: String? = sara.id,
        events: List<Event> = listOf(school, preschool),
        tasks: List<FamilyTask> = emptyList(),
        requests: List<HandoffRequest> = emptyList(),
        rules: List<PackingRule> = emptyList(),
        checks: Map<String, PackingChecks> = emptyMap(),
    ): TodayInput {
        val local = now.toLocalDateTime(zone)
        return TodayInput(
            today = local.date,
            now = local.time,
            zone = zone,
            me = me,
            people = people,
            children = listOf(ella, simon),
            occurrences = EventExpander.expand(events, local.date.minusDays(1), local.date.plusDays(10), zone),
            packingRules = rules,
            packingChecks = checks,
            tasks = tasks,
            requests = requests,
        )
    }

    fun input(
        now: Instant = this.now,
        me: String? = sara.id,
        role: Role? = Role.Parent,
        events: List<Event> = listOf(school, preschool),
        tasks: List<FamilyTask> = emptyList(),
        requests: List<HandoffRequest> = emptyList(),
        rules: List<PackingRule> = emptyList(),
        checks: Map<String, PackingChecks> = emptyMap(),
        morningAt: LocalTime? = null,
        quiet: QuietSettings? = null,
    ) = ReminderInput(
        base = base(now, me, events, tasks, requests, rules, checks),
        now = now,
        role = role,
        morningAt = morningAt,
        quiet = quiet,
    )
}

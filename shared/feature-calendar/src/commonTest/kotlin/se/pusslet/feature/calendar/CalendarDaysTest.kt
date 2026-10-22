package se.pusslet.feature.calendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventOccurrence
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.model.SickDay
import se.pusslet.core.sick.SickDays
import se.pusslet.data.repository.CalendarLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarDaysTest {
    private val day = LocalDate(2026, 10, 13)
    private val sara = FamilyPerson("m-sara", "Sara", MemberColor.Sage, isChild = false, role = Role.Parent)
    private val johan = FamilyPerson("m-johan", "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent)
    private val ella = FamilyPerson("c-ella", "Ella", MemberColor.Rose, isChild = true)
    private val simon = FamilyPerson("c-simon", "Simon", MemberColor.Ochre, isChild = true)
    private val people = listOf(sara, johan, ella, simon)

    private fun occurrence(
        id: String,
        start: LocalTime,
        end: LocalTime,
        who: List<FamilyPerson>,
        tags: List<String> = listOf("skola"),
        dropOff: Handoff? = null,
        pickUp: Handoff? = null,
        allDay: Boolean = false,
    ): EventOccurrence {
        val event = Event(
            id,
            id,
            LocalDateTime(day, start),
            LocalDateTime(day, end),
            allDay = allDay,
            participantIds = who.map { it.id },
            dropOff = dropOff,
            pickUp = pickUp,
            tags = tags,
        )
        return EventOccurrence(event, event.start, event.end, day)
    }

    @Test
    fun schoolAndFritidsOfOneChildShareALine() {
        val school = occurrence("skola", LocalTime(8, 15), LocalTime(13, 0), listOf(ella), dropOff = Handoff(johan.id, LocalTime(8, 5)))
        val fritids =
            occurrence("fritids", LocalTime(13, 0), LocalTime(16, 30), listOf(ella), tags = listOf("fritids"), pickUp = Handoff(sara.id))

        val line = CalendarDays.routineLines(day, listOf(fritids, school), people, me = sara.id).single()

        assertEquals(listOf(ella), line.children)
        assertEquals(listOf(HandoffKind.DropOff, HandoffKind.PickUp), line.stops.map { it.kind })
        assertEquals(listOf(LocalTime(8, 5), LocalTime(16, 30)), line.stops.map { it.time })
        assertTrue(line.stops[1].isMe)
        // A tap opens the first of them.
        assertEquals("skola", line.occurrence.event.id)
    }

    @Test
    fun linesAreSortedByTheirFirstHandoff() {
        val ellas = occurrence("skola", LocalTime(8, 15), LocalTime(16, 30), listOf(ella), dropOff = Handoff(johan.id, LocalTime(8, 5)))
        val simons = occurrence(
            "forskola",
            LocalTime(8, 0),
            LocalTime(16, 0),
            listOf(simon),
            tags = listOf("förskola"),
            dropOff = Handoff(johan.id, LocalTime(7, 50)),
        )
        val both = occurrence("utflykt", LocalTime(9, 0), LocalTime(15, 0), listOf(ella, simon), pickUp = Handoff(null))

        val lines = CalendarDays.routineLines(day, listOf(ellas, simons, both), people, me = null)

        assertEquals(listOf(listOf(simon), listOf(ella), listOf(ella, simon)), lines.map { it.children })
        assertTrue(lines.last().stops.single().isOpen)
    }

    @Test
    fun aRoutineWithoutHandoffsIsStillAQuietLine() {
        val school = occurrence("skola", LocalTime(8, 15), LocalTime(16, 30), listOf(ella))

        val line = CalendarDays.routineLines(day, listOf(school), people, me = null).single()

        assertTrue(line.stops.isEmpty())
        assertEquals(null, line.firstOpen)
    }

    @Test
    fun allDayAndAdultOnlyEventsStayEvents() {
        val allDay = occurrence("lov", LocalTime(0, 0), LocalTime(0, 0), listOf(ella), allDay = true)
        val teacher = occurrence("jobb", LocalTime(8, 0), LocalTime(16, 0), listOf(sara))
        val training = occurrence("fotboll", LocalTime(17, 30), LocalTime(18, 30), listOf(simon), tags = emptyList())

        val built = CalendarDays.build(
            day,
            day,
            listOf(training, teacher, allDay),
            people,
            schedules = emptyList(),
            personFilter = emptySet(),
            layers = CalendarLayer.entries.toSet(),
            me = null,
        ).single()

        assertTrue(built.routines.isEmpty())
        // All-day first, then by start.
        assertEquals(listOf("lov", "jobb", "fotboll"), built.events.map { it.event.id })
    }

    /** ADR-047: Ella home sick; her school line is gone, Simon's stays, and the day says why. */
    @Test
    fun aSickChildsDayIsAQuietLineWithoutHerSchool() {
        val ellasSchool = occurrence("skola", LocalTime(8, 15), LocalTime(13, 0), listOf(ella), pickUp = Handoff(sara.id, LocalTime(15, 0)))
        val simonsPreschool = occurrence("förskola", LocalTime(8, 0), LocalTime(16, 0), listOf(simon), dropOff = Handoff(johan.id))
        val sick = listOf(SickDay(SickDay.idFor(ella.id, day), ella.id, day, homeWith = sara.id))
        val occurrences = SickDays.apply(listOf(ellasSchool, simonsPreschool), sick, setOf(ella.id, simon.id)).occurrences

        val built = CalendarDays.build(
            day,
            day,
            occurrences,
            people,
            schedules = emptyList(),
            personFilter = emptySet(),
            layers = CalendarLayer.entries.toSet(),
            me = sara.id,
            sickDays = sick,
        ).single()

        assertEquals(listOf(SickLine(ella, sara)), built.sick)
        assertEquals(listOf(listOf(simon)), built.routines.map { it.children })
        val onlySimon = CalendarDays.build(
            day,
            day,
            occurrences,
            people,
            emptyList(),
            setOf(simon.id),
            CalendarLayer.entries.toSet(),
            null,
            sick,
        )
        assertTrue(onlySimon.single().sick.isEmpty(), "the filter on Simon hides Ella's line")
    }

    @Test
    fun onlyInteBestamtIsColouredInTheLine() {
        val open = Segment("Lämning 07:50 – Inte bestämt", "Inte bestämt", SpanStyle(color = Color.Red))
        val taken = Segment("Johan hämtar 15:30")
        val full = "Simon: " + open.text + SEPARATOR + taken.text

        val text = annotate(full, listOf(open, taken))

        assertEquals(full, text.text)
        val span = text.spanStyles.single()
        assertEquals("Inte bestämt", full.substring(span.start, span.end))
        assertEquals(Color.Red, span.item.color)
    }
}

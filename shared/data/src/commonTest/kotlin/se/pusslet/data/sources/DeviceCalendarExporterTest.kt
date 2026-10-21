package se.pusslet.data.sources

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.Role
import se.pusslet.data.repository.DeviceCalendar
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A phone calendar in memory: calendar id -> the family events written there (only the app's rows). */
private class FakePhoneCalendar : DeviceCalendarSource {
    val rows = HashMap<Long, MutableMap<String, Event>>()

    override fun hasPermission() = true

    override suspend fun calendars(): List<DeviceCalendar> = emptyList()

    override suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate, ownerMemberId: String?) = emptyList<Event>()

    override suspend fun exportEvents(calendarId: Long, events: List<Event>): Int {
        val calendar = rows.getOrPut(calendarId) { HashMap() }
        events.forEach { calendar[it.id] = it }
        return events.size
    }

    override suspend fun removeExported(calendarId: Long, keep: Set<String>): Int {
        val calendar = rows[calendarId] ?: return 0
        val gone = calendar.keys.filter { it !in keep }
        gone.forEach(calendar::remove)
        return gone.size
    }

    override suspend fun ensureAppCalendar(name: String): Long = 7L

    override fun changes(): Flow<Unit> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCalendarExporterTest {
    private val day = LocalDate(2026, 10, 21)

    private fun event(id: String, title: String, h: Int, rrule: String? = null) =
        Event(id, title, LocalDateTime(day, LocalTime(h, 0)), LocalDateTime(day, LocalTime(h + 1, 0)), rrule = rrule)

    @Test
    fun theChosenCalendarFollowsTheFamily() = runTest {
        val world = SubscriptionWorld()
        val phone = FakePhoneCalendar()
        val exporter = DeviceCalendarExporter(world.calendar, phone, world.prefs, world.sessions)
        exporter.start(backgroundScope)

        world.calendar.saveEvent(event("e-dans", "Dans", 17))
        world.prefs.set(Prefs.DeviceCalendarExport, 7L)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals<Set<String>?>(setOf("e-dans"), phone.rows[7L]?.keys)

        // A series with one occurrence moved: the move is its own event and the series skips that date.
        world.calendar.saveEvent(event("e-sim", "Simskola", 9, rrule = "FREQ=WEEKLY"))
        world.calendar.saveEvent(event("e-sim-moved", "Simskola", 10).copy(seriesId = "e-sim", originalDate = day.plusWeek()))
        world.calendar.deleteEvent("e-dans")
        advanceTimeBy(3.seconds)
        runCurrent()
        val rows = phone.rows.getValue(7L)
        assertEquals(setOf("e-sim", "e-sim-moved"), rows.keys, "the deleted event is gone from the phone too")
        assertEquals(listOf(day.plusWeek()), rows.getValue("e-sim").exDates)
        assertEquals(null, rows.getValue("e-sim-moved").rrule)

        world.prefs.set(Prefs.DeviceCalendarExport, -1L)
        exporter.leave(7L)
        assertEquals(emptySet<String>(), phone.rows.getValue(7L).keys, "switched off: the copies are taken away")
    }

    @Test
    fun caregiversNeverWriteToThePhone() = runTest {
        val world = SubscriptionWorld(role = Role.Caregiver)
        val phone = FakePhoneCalendar()
        DeviceCalendarExporter(world.calendar, phone, world.prefs, world.sessions).start(backgroundScope)
        world.calendar.saveEvent(event("e-dans", "Dans", 17))
        world.prefs.set(Prefs.DeviceCalendarExport, 7L)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(null, phone.rows[7L])
    }

    private fun LocalDate.plusWeek() = LocalDate.fromEpochDays(toEpochDays() + 7)
}

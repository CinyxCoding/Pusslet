package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.Role
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.DeviceCalendar
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCalendarExportTest {
    /** A phone calendar: what was exported, keyed by family event id. */
    private class Phone(var permitted: Boolean = true) : DeviceCalendarSource {
        val exported = mutableMapOf<String, Event>()

        override fun hasPermission() = permitted

        override suspend fun calendars() = emptyList<DeviceCalendar>()

        override suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate, ownerMemberId: String?) = emptyList<Event>()

        override suspend fun exportEvents(calendarId: Long, events: List<Event>): Int {
            events.forEach { exported[it.id] = it }
            return events.size
        }

        override suspend fun removeExported(calendarId: Long, keepEventIds: Set<String>): Int {
            val stale = exported.keys - keepEventIds
            stale.forEach { exported.remove(it) }
            return stale.size
        }

        override suspend fun ensureAppCalendar(name: String): Long? = 1

        override fun changes(): Flow<Unit> = flowOf()
    }

    private class MemoryPrefs : PreferenceDao {
        val values = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observe(key: String): Flow<String?> = kotlinx.coroutines.flow.flow { values.collect { emit(it[key]) } }

        override suspend fun get(key: String) = values.value[key]

        override suspend fun put(entity: PreferenceEntity) {
            values.value = values.value + (entity.key to entity.value)
        }

        override suspend fun remove(key: String) {
            values.value = values.value - key
        }

        override suspend fun clear() {
            values.value = emptyMap()
        }
    }

    private val sessions = FixedSessionProvider(Session("f", "m-sara", "u", Role.Parent))
    private val docs = DocumentStore(InMemorySyncLocalStore(), sessions)
    private val prefs = PreferencesRepository(MemoryPrefs())
    private val phone = Phone()
    private val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, phone, sessions)
    private val export = DeviceCalendarExport(calendar, phone, prefs)

    private fun event(id: String, day: Int) =
        Event(id, id, LocalDateTime(2026, 10, day, 17, 0), LocalDateTime(LocalDate(2026, 10, day), LocalTime(18, 0)))

    @Test
    fun exportsTheFamilyAndRemovesWhatWasDeleted() = runTest {
        calendar.saveEvent(event("fotboll", 13))
        calendar.saveEvent(event("dans", 14))
        assertFalse(export.syncOnce(), "nothing happens until a calendar is chosen")

        prefs.set(Prefs.DeviceCalendarExport, 7L)
        assertTrue(export.syncOnce())
        assertEquals(setOf("fotboll", "dans"), phone.exported.keys)

        calendar.deleteEvent("dans")
        export.syncOnce()
        assertEquals(setOf("fotboll"), phone.exported.keys)
    }

    @Test
    fun withoutCalendarPermissionNothingIsWritten() = runTest {
        phone.permitted = false
        prefs.set(Prefs.DeviceCalendarExport, 7L)
        calendar.saveEvent(event("fotboll", 13))
        assertFalse(export.syncOnce())
        assertTrue(phone.exported.isEmpty())
    }
}

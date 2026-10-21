package se.pusslet.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Event
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.plusDays
import se.pusslet.data.repository.DeviceCalendar
import se.pusslet.data.repository.DeviceCalendarSource
import kotlin.time.Instant

/**
 * The phone's calendars through CalendarContract (Google, Exchange/Outlook, Samsung … accounts on the device).
 * Reading uses the Instances table (the provider expands recurrences). Family events are written with a stable
 * UID_2445 ("pusslet-<id>") so re-exporting updates instead of duplicating. Permissions are requested in context.
 */
class AndroidDeviceCalendarSource(private val context: Context, private val zone: TimeZone) : DeviceCalendarSource {
    private val log = Logger.withTag("DeviceCalendar")
    private val resolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun canWrite(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val result = ArrayList<DeviceCalendar>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(4)
                result += DeviceCalendar(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    accountName = c.getString(2).orEmpty(),
                    color = c.getInt(3),
                    writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    ownedByApp = c.getString(5) == CalendarContract.ACCOUNT_TYPE_LOCAL && c.getString(2) == APP_ACCOUNT,
                )
            }
        }
        result
    }

    override suspend fun events(calendarIds: Set<Long>, from: LocalDate, to: LocalDate, ownerMemberId: String?): List<Event> =
        withContext(Dispatchers.IO) {
            if (!hasPermission() || calendarIds.isEmpty()) return@withContext emptyList()
            val begin = from.atStartOfDayIn(zone).toEpochMilliseconds()
            val end = to.plusDays(1).atStartOfDayIn(zone).toEpochMilliseconds()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, begin)
                ContentUris.appendId(it, end)
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.UID_2445,
            )
            val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
            val result = ArrayList<Event>()
            resolver.query(uri, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    // Our own exported events are already in the family calendar.
                    if (c.getString(7)?.startsWith(UID_PREFIX) == true) continue
                    val allDay = c.getInt(4) == 1
                    // All-day instances are stored in UTC midnight.
                    val tz = if (allDay) TimeZone.UTC else zone
                    val start = Instant.fromEpochMilliseconds(c.getLong(1)).toLocalDateTime(tz)
                    val stop = Instant.fromEpochMilliseconds(c.getLong(2)).toLocalDateTime(tz)
                    result += Event(
                        id = "device:${c.getLong(0)}@${c.getLong(1)}",
                        title = c.getString(3).orEmpty().ifBlank { "–" },
                        start = start,
                        end = if (stop < start) start else stop,
                        allDay = allDay,
                        timeZoneId = zone.id,
                        location = c.getString(5).orEmpty(),
                        participantIds = listOfNotNull(ownerMemberId),
                        sharedWithCaregivers = false,
                    )
                }
            }
            result
        }

    override suspend fun exportEvents(calendarId: Long, events: List<Event>): Int = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext 0
        var touched = 0
        val rows = exported(calendarId)
        // A moved single occurrence is an event of its own here (DeviceExport.prepare gives its series the EXDATE).
        for (event in events) {
            val values = toValues(calendarId, event)
            val existing = rows[event.id]
            try {
                if (existing != null) {
                    resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), values, null, null)
                } else {
                    resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }
                touched++
            } catch (e: IllegalArgumentException) {
                log.w { "Could not export an event: ${e::class.simpleName}" }
            } catch (e: SecurityException) {
                log.w(e) { "Calendar write not permitted" }
                return@withContext touched
            }
        }
        touched
    }

    override suspend fun removeExported(calendarId: Long, keep: Set<String>): Int = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext 0
        var removed = 0
        exported(calendarId).filterKeys { it !in keep }.values.forEach { row ->
            try {
                removed += resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row), null, null)
            } catch (e: SecurityException) {
                log.w(e) { "Calendar write not permitted" }
                return@withContext removed
            }
        }
        removed
    }

    /** The app's copies in [calendarId]: family event id -> row id. Only rows with our UID prefix; the user's own are never touched. */
    private fun exported(calendarId: Long): Map<String, Long> = resolver.query(
        CalendarContract.Events.CONTENT_URI,
        arrayOf(CalendarContract.Events._ID, CalendarContract.Events.UID_2445),
        "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.UID_2445} LIKE ? AND " +
            "${CalendarContract.Events.DELETED} = 0",
        arrayOf(calendarId.toString(), "$UID_PREFIX%"),
        null,
    )?.use { c ->
        buildMap { while (c.moveToNext()) c.getString(1)?.removePrefix(UID_PREFIX)?.let { put(it, c.getLong(0)) } }
    }.orEmpty()

    private fun toValues(calendarId: Long, e: Event): ContentValues {
        val eventZone = runCatching { TimeZone.of(e.timeZoneId) }.getOrDefault(zone)
        val tz = if (e.allDay) TimeZone.UTC else eventZone
        val start = e.start.toInstant(tz).toEpochMilliseconds()
        val end = e.end.toInstant(tz).toEpochMilliseconds()
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, e.title)
            put(CalendarContract.Events.EVENT_LOCATION, e.location)
            put(CalendarContract.Events.DESCRIPTION, e.note)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz.id)
            put(CalendarContract.Events.UID_2445, uid(e.id))
            if (e.isRecurring) {
                // Recurring events need DURATION instead of DTEND.
                val seconds = EventExpander.wallClockDuration(e.start, e.end).inWholeSeconds.coerceAtLeast(60)
                put(CalendarContract.Events.RRULE, e.rrule)
                put(CalendarContract.Events.DURATION, if (e.allDay) "P${seconds / 86_400}D" else "PT${seconds}S")
                putNull(CalendarContract.Events.DTEND)
                if (e.exDates.isNotEmpty()) {
                    put(
                        CalendarContract.Events.EXDATE,
                        e.exDates.joinToString(",") { d ->
                            val dt = LocalDateTime(
                                d,
                                if (e.allDay) LocalTime(0, 0) else e.start.time,
                            ).toInstant(tz).toLocalDateTime(TimeZone.UTC)
                            "%04d%02d%02dT%02d%02d%02dZ".format(dt.year, dt.month.ordinal + 1, dt.day, dt.hour, dt.minute, dt.second)
                        },
                    )
                }
            } else {
                put(CalendarContract.Events.DTEND, end)
            }
        }
    }

    override suspend fun ensureAppCalendar(name: String): Long? = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext null
        calendars().firstOrNull { it.ownedByApp }?.let { return@withContext it.id }
        val uri = asSyncAdapter(CalendarContract.Calendars.CONTENT_URI)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, APP_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.CALENDAR_COLOR, APP_CALENDAR_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, APP_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
        }
        runCatching { resolver.insert(uri, values)?.let { ContentUris.parseId(it) } }.getOrNull()
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        if (hasPermission()) resolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, APP_ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun uid(eventId: String) = "$UID_PREFIX$eventId"

    private companion object {
        const val APP_ACCOUNT = "Pusslet"
        const val UID_PREFIX = "pusslet-"
        const val APP_CALENDAR_COLOR = 0xFF4A6851.toInt()
    }
}

package se.pusslet.data.sources

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.pusslet.core.calendar.DeviceExport
import se.pusslet.core.model.Event
import se.pusslet.core.model.Role
import se.pusslet.data.AppProcessWork
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the family's events in the phone calendar chosen in Inställningar (BRIEF §3, ADR-040): a moment after any change
 * while the app runs, events are written or updated and copies of events no longer in the family are removed. Only
 * rows the app wrote (UID "pusslet-…") are touched. Parents only, as the choice in Inställningar.
 */
class DeviceCalendarExporter(
    private val calendar: CalendarRepository,
    private val devices: DeviceCalendarSource,
    private val prefs: PreferencesRepository,
    private val sessions: SessionProvider,
    private val debounce: Duration = 2.seconds,
) : AppProcessWork {
    private val log = Logger.withTag("CalendarExport")

    @OptIn(FlowPreview::class)
    override fun start(scope: CoroutineScope) {
        scope.launch {
            val parent = sessions.session.map { it.hasFamily && it.role == Role.Parent }.distinctUntilChanged()
            combine(prefs.observe(Prefs.DeviceCalendarExport), calendar.events, parent) { target, events, isParent ->
                Triple(target, events, isParent)
            }
                .debounce(debounce)
                .catch { e -> log.w(e) { "Calendar export stopped" } }
                .collectLatest { (target, events, isParent) ->
                    if (isParent && target >= 0) sync(target, events)
                }
        }
    }

    /** Writes [events] into [target] and removes copies of events that are gone. Never throws for the phone's calendar. */
    suspend fun sync(target: Long, events: List<Event>) {
        try {
            val export = DeviceExport.prepare(events)
            val written = devices.exportEvents(target, export)
            val removed = devices.removeExported(target, export.mapTo(HashSet()) { it.id })
            // Counts only: no titles or names in the log.
            log.i { "Exported $written events, removed $removed" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.w(e) { "Could not export to the phone calendar" }
        }
    }

    /** Another calendar (or none) was chosen: the copies in the one before are taken away. */
    suspend fun leave(previous: Long) {
        if (previous >= 0) devices.removeExported(previous, emptySet())
    }
}

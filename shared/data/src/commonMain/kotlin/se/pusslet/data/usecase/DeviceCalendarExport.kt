package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import kotlin.time.Duration.Companion.seconds

/**
 * "Familjehändelser syns i enhetens kalender": keeps the chosen device calendar (Google, Exchange or the app's own local
 * calendar) in step with the family's events – on every change while the app runs, and from a periodic worker.
 */
class DeviceCalendarExport(
    private val calendar: CalendarRepository,
    private val device: DeviceCalendarSource,
    private val prefs: PreferencesRepository,
) {
    private val log = Logger.withTag("CalendarExport")

    /** One pass: export everything and remove what was deleted. Returns false when export is off or not permitted. */
    suspend fun syncOnce(): Boolean {
        val target = prefs.get(Prefs.DeviceCalendarExport)
        if (target < 0 || !device.hasPermission()) return false
        val events = calendar.events.first()
        return try {
            device.exportEvents(target, events)
            device.removeExported(target, events.map { it.id }.toSet())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Export failed: ${e::class.simpleName}" }
            false
        }
    }

    /** Follows changes; edits that come in a burst (typing, a sync) are written once. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(prefs.observe(Prefs.DeviceCalendarExport), calendar.events) { target, _ -> target }
                .debounce(DEBOUNCE)
                .collect { target -> if (target >= 0) syncOnce() }
        }
    }

    private companion object {
        val DEBOUNCE = 2.seconds
    }
}

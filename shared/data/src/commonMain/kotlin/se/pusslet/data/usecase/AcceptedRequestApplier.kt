package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import se.pusslet.core.calendar.EffectiveHandoffs
import se.pusslet.core.calendar.EffectiveHandoffs.matches
import se.pusslet.core.model.EventSource
import se.pusslet.core.model.Role
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock

/**
 * On parents' phones only: writes a caregiver's accepted answer into the event ("Bengt hämtar Ella fredag") and stamps
 * the request with `appliedAt` (ADR-030). Caregivers may not write events, so their phones never run this. Running it twice,
 * or on two parents' phones at once, gives the same result.
 */
class AcceptedRequestApplier(
    private val calendar: CalendarRepository,
    private val assigner: HandoffAssigner,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    private val log = Logger.withTag("Handoffs")

    /** Applies whatever is waiting; returns how many answers were written in. */
    suspend fun applyPending(): Int {
        if (sessions.session.value.role != Role.Parent) return 0
        var applied = 0
        for (request in EffectiveHandoffs.pending(calendar.handoffRequests.first())) {
            val answeredBy = request.respondedBy ?: continue
            val occurrence = calendar.occurrences(request.occurrenceDate, request.occurrenceDate, zone).first()
                .firstOrNull { it.source == EventSource.Family && request.matches(it) }
            if (occurrence == null) {
                // The occurrence was removed or moved after the answer: nothing to write into, the request just stays.
                continue
            }
            assigner.assign(occurrence, request.kind, answeredBy)
            calendar.saveHandoffRequest(request.copy(appliedAt = clock.now()))
            applied++
        }
        if (applied > 0) log.i { "Applied $applied accepted hand-off answers" }
        return applied
    }

    /** Watches the requests while this phone belongs to a parent. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(
            sessions.session.map { it.role }.distinctUntilChanged(),
            calendar.handoffRequests.map { requests -> EffectiveHandoffs.pending(requests).map { it.id }.toSet() }.distinctUntilChanged(),
        ) { role, pending -> role == Role.Parent && pending.isNotEmpty() }
            .collectLatest { work -> if (work) applyPending() }
    }
}

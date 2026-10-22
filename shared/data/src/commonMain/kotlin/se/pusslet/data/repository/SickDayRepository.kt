package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import se.pusslet.core.model.SickDay
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore

/**
 * Sick days (ADR-047): "Ella är sjuk idag", who stays home, VAB and the reports. One document per child and day, so the
 * same child reported twice the same morning stays one day.
 */
class SickDayRepository(private val docs: DocumentStore, private val sessions: SessionProvider) {
    /** The same as [CalendarRepository.sickDays], for what writes them. */
    val days: Flow<List<SickDay>> = docs.observeAll(Collections.SickDays, SickDay.serializer())

    /** Reports [childIds] sick on [date]; a day already reported keeps what was filled in. */
    suspend fun report(childIds: List<String>, date: LocalDate): List<SickDay> {
        val me = sessions.session.value.memberId
        return childIds.map { child ->
            val id = SickDay.idFor(child, date)
            docs.get(Collections.SickDays, id, SickDay.serializer())
                ?: SickDay(id, child, date, reportedBy = me).also { save(it) }
        }
    }

    /**
     * "Sjuk idag också": a new day for the child, with the same person at home and the same VAB, and work already told.
     * The school is told again, since many schools want each day reported.
     */
    suspend fun stillSick(previous: SickDay, today: LocalDate): SickDay {
        val id = SickDay.idFor(previous.childId, today)
        docs.get(Collections.SickDays, id, SickDay.serializer())?.let { return it }
        val day = SickDay(
            id = id,
            childId = previous.childId,
            date = today,
            reportedBy = sessions.session.value.memberId,
            homeWith = previous.homeWith,
            vab = previous.vab,
            vabPart = previous.vabPart,
            workTold = previous.workTold,
        )
        save(day)
        return day
    }

    suspend fun update(id: String, transform: (SickDay) -> SickDay) {
        docs.update(Collections.SickDays, id, SickDay.serializer(), transform)
    }

    /** "Frisk igen": nobody is asked "Fortfarande sjuk?" about this child. */
    suspend fun wellAgain(day: SickDay) = update(day.id) { it.copy(wellAgain = true) }

    suspend fun setVabReported(days: List<SickDay>) = days.forEach { d -> update(d.id) { it.copy(vabReported = true) } }

    /** "Ångra": the report was a mistake; the day is as before. */
    suspend fun undo(id: String) = docs.delete(Collections.SickDays, id)

    private suspend fun save(day: SickDay) = docs.put(Collections.SickDays, day.id, day, SickDay.serializer())
}

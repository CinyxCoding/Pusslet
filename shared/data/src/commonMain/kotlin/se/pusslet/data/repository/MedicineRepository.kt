package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock
import kotlin.time.Instant

/** The medicine and fever log and courses (ADR-050). Parents only: caregivers neither read nor write them. */
class MedicineRepository(
    private val docs: DocumentStore,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    val entries: Flow<List<MedicineEntry>> = docs.observeAll(Collections.MedicineLog, MedicineEntry.serializer())
    val courses: Flow<List<MedicineCourse>> = docs.observeAll(Collections.MedicineCourses, MedicineCourse.serializer())

    /** "Gav Alvedon 5 ml"; [slot] when it is a planned dose of [courseId]. */
    suspend fun logDose(
        childId: String,
        medicine: String,
        dose: String,
        at: Instant = clock.now(),
        courseId: String? = null,
        slot: LocalDateTime? = null,
    ): MedicineEntry = save(
        MedicineEntry(ids.newId(), childId, at, medicine.trim(), dose.trim(), by = me(), courseId = courseId, slot = slot),
    )

    /** "Tempade 38,4". */
    suspend fun logTemperature(childId: String, temperature: Double, at: Instant = clock.now()): MedicineEntry =
        save(MedicineEntry(ids.newId(), childId, at, temperature = temperature, by = me()))

    /** "Ångra" and mistakes. */
    suspend fun delete(entry: MedicineEntry) = docs.delete(Collections.MedicineLog, entry.id)

    suspend fun saveCourse(course: MedicineCourse) = docs.put(Collections.MedicineCourses, course.id, course, MedicineCourse.serializer())

    fun newCourseId(): String = ids.newId()

    /** "Avsluta kuren": no more reminders; the doses given stay in the log. */
    suspend fun stopCourse(
        id: String,
    ) = docs.update(Collections.MedicineCourses, id, MedicineCourse.serializer()) { it.copy(stopped = true) }

    suspend fun deleteCourse(id: String) = docs.delete(Collections.MedicineCourses, id)

    private fun me(): String? = sessions.session.value.memberId

    private suspend fun save(entry: MedicineEntry): MedicineEntry {
        docs.put(Collections.MedicineLog, entry.id, entry, MedicineEntry.serializer())
        return entry
    }
}

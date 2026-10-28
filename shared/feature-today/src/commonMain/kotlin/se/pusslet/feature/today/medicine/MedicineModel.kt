package se.pusslet.feature.today.medicine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry

/** One planned dose on Idag's "Medicin idag" (ADR-050). */
data class MedicineDoseRow(val slot: MedicineRules.Slot, val child: FamilyPerson, val givenBy: FamilyPerson?)

/** A course in the medicine view, with where it stands today. */
data class CourseRow(
    val course: MedicineCourse,
    val child: FamilyPerson,
    /** Today's doses; empty when the course has not started or is over. */
    val today: List<MedicineRules.Slot>,
    /** Doses logged for the course so far. */
    val given: Int,
    val total: Int,
    val lastDay: LocalDate,
)

/** One line of a child's log: a dose or a temperature, when (in the family's zone) and who wrote it. */
data class LogRow(val entry: MedicineEntry, val local: LocalDateTime, val by: FamilyPerson?, val fever: Boolean)

object MedicineModel {
    /** Today's doses of every course, by time; for parents only (the caller checks). */
    fun today(
        date: LocalDate,
        courses: List<MedicineCourse>,
        log: List<MedicineEntry>,
        people: Map<String, FamilyPerson>,
    ): List<MedicineDoseRow> =
        MedicineRules.slotsOn(courses, date, log).mapNotNull { slot ->
            val child = people[slot.course.childId] ?: return@mapNotNull null
            MedicineDoseRow(slot, child, slot.given?.by?.let { people[it] })
        }

    /** A child's running and coming courses, the running ones first. */
    fun courses(
        childId: String,
        date: LocalDate,
        courses: List<MedicineCourse>,
        log: List<MedicineEntry>,
        people: Map<String, FamilyPerson>,
    ): List<CourseRow> = courses
        .filter { it.childId == childId && !it.stopped && MedicineRules.lastDay(it) >= date }
        .sortedWith(compareBy({ it.start }, { it.medicine.lowercase() }))
        .mapNotNull { c ->
            val child = people[c.childId] ?: return@mapNotNull null
            CourseRow(
                course = c,
                child = child,
                today = MedicineRules.slots(c, date, log),
                given = log.count { it.courseId == c.id },
                total = MedicineRules.totalDoses(c),
                lastDay = MedicineRules.lastDay(c),
            )
        }

    /** The child's log, newest first. */
    fun log(childId: String, log: List<MedicineEntry>, people: Map<String, FamilyPerson>, zone: TimeZone): List<LogRow> =
        MedicineRules.log(log, childId).map { e -> row(e, people, zone) }

    fun row(e: MedicineEntry, people: Map<String, FamilyPerson>, zone: TimeZone): LogRow =
        LogRow(e, e.at.toLocalDateTime(zone), e.by?.let { people[it] }, MedicineRules.isFever(e))

    /** "38,4" with the language's decimal separator. */
    fun temperature(value: Double, decimalSeparator: Char): String {
        val tenths = kotlin.math.round(value * TEN).toLong()
        return "${tenths / TEN.toLong()}$decimalSeparator${tenths % TEN.toLong()}"
    }

    private const val TEN = 10.0
}

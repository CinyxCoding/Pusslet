package se.pusslet.core.medicine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.time.plusDays
import kotlin.time.Instant

/**
 * The medicine and fever log as data (ADR-050). Pure, so Idag, the log and the reminders agree. The app gives no medical
 * advice: it shows what was given and when, and the times the parents themselves chose for a course.
 */
object MedicineRules {
    /** One planned dose of a course and whether it has been logged. */
    data class Slot(val course: MedicineCourse, val at: LocalDateTime, val given: MedicineEntry?) {
        val done: Boolean get() = given != null
    }

    /** A temperature at or above this is fever (the usual definition, shown as "feber"; not advice). */
    const val FEVER_FROM = 38.0

    /** The last day of the course. */
    fun lastDay(course: MedicineCourse): LocalDate = course.start.plusDays(course.days.coerceAtLeast(1) - 1)

    fun isActive(course: MedicineCourse, date: LocalDate): Boolean = !course.stopped && date >= course.start && date <= lastDay(course)

    /** Courses that run on [date], by first time then medicine. */
    fun activeOn(courses: List<MedicineCourse>, date: LocalDate): List<MedicineCourse> =
        courses.filter { isActive(it, date) }.sortedWith(compareBy({ it.times.firstOrNull() }, { it.medicine.lowercase() }))

    /** The course's doses on [date], each with the logged dose for it, if any. */
    fun slots(course: MedicineCourse, date: LocalDate, entries: List<MedicineEntry>): List<Slot> {
        if (!isActive(course, date)) return emptyList()
        val logged = entries.filter { it.courseId == course.id && it.slot != null }.associateBy { it.slot }
        return course.times.distinct().sorted().map { t ->
            val at = LocalDateTime(date, t)
            Slot(course, at, logged[at])
        }
    }

    /** Every course's doses on [date], by time. */
    fun slotsOn(courses: List<MedicineCourse>, date: LocalDate, entries: List<MedicineEntry>): List<Slot> =
        activeOn(courses, date).flatMap { slots(it, date, entries) }.sortedWith(compareBy({ it.at }, { it.course.medicine.lowercase() }))

    /** "Dos 4 av 30": which dose of the whole course this is, counted from the first. */
    fun doseNumber(course: MedicineCourse, at: LocalDateTime): Int {
        val perDay = course.times.distinct().size
        val day = (at.date.toEpochDays() - course.start.toEpochDays()).toInt()
        return day * perDay + course.times.distinct().sorted().indexOf(at.time) + 1
    }

    fun totalDoses(course: MedicineCourse): Int = course.days * course.times.distinct().size

    /** A child's log, newest first. */
    fun log(
        entries: List<MedicineEntry>,
        childId: String,
    ): List<MedicineEntry> = entries.filter { it.childId == childId }.sortedByDescending { it.at }

    /** The last dose of [medicine] (any spelling of the case) for the child, for "Senast 08:15". */
    fun lastDose(entries: List<MedicineEntry>, childId: String, medicine: String): MedicineEntry? = entries
        .filter { it.childId == childId && it.isDose && it.medicine.trim().equals(medicine.trim(), ignoreCase = true) }
        .maxByOrNull { it.at }

    /** The child's latest temperature. */
    fun lastTemperature(entries: List<MedicineEntry>, childId: String): MedicineEntry? =
        entries.filter { it.childId == childId && it.temperature != null }.maxByOrNull { it.at }

    /** Medicines given before, most used first, to offer as one-tap choices. */
    fun usual(entries: List<MedicineEntry>, courses: List<MedicineCourse>): List<String> =
        (entries.filter { it.isDose }.map { it.medicine.trim() } + courses.map { it.medicine.trim() })
            .filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }
            .values
            .sortedByDescending { it.size }
            .map { it.first() }
            .take(USUAL)

    /** The dose last given of [medicine], to fill in the field again. */
    fun usualDose(entries: List<MedicineEntry>, medicine: String): String? = entries
        .filter { it.isDose && it.medicine.trim().equals(medicine.trim(), ignoreCase = true) && it.dose.isNotBlank() }
        .maxByOrNull { it.at }?.dose

    /** "38,4" style reading with comma or dot; null when it is not a body temperature (34–43 °C). */
    fun parseTemperature(text: String): Double? {
        val value = text.trim().removeSuffix("°C").removeSuffix("°").trim().replace(',', '.').toDoubleOrNull() ?: return null
        return value.takeIf { it in BODY_MIN..BODY_MAX }
    }

    fun isFever(entry: MedicineEntry): Boolean = (entry.temperature ?: 0.0) >= FEVER_FROM

    /** Times for "N gånger om dagen", spread over the waking day; the parent changes them if the leaflet says otherwise. */
    fun defaultTimes(perDay: Int): List<LocalTime> = when (perDay.coerceIn(1, MedicineCourse.MAX_TIMES)) {
        1 -> listOf(LocalTime(8, 0))
        2 -> listOf(LocalTime(8, 0), LocalTime(20, 0))
        3 -> listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0))
        4 -> listOf(LocalTime(7, 0), LocalTime(12, 0), LocalTime(17, 0), LocalTime(22, 0))
        5 -> listOf(LocalTime(7, 0), LocalTime(11, 0), LocalTime(15, 0), LocalTime(19, 0), LocalTime(23, 0))
        else -> listOf(LocalTime(6, 0), LocalTime(9, 0), LocalTime(12, 0), LocalTime(15, 0), LocalTime(18, 0), LocalTime(21, 0))
    }

    /** How long the log is kept: it is for the illness at hand, not a medical record. */
    const val KEEP_DAYS = 90

    /** Entries older than [keepFrom] go away. */
    fun expired(entries: List<MedicineEntry>, keepFrom: Instant): List<MedicineEntry> = entries.filter { it.at < keepFrom }

    /** Courses that ended (or were stopped) before [keepFrom]'s day. */
    fun expiredCourses(courses: List<MedicineCourse>, keepFrom: LocalDate): List<MedicineCourse> = courses.filter { lastDay(it) < keepFrom }

    private const val USUAL = 6
    private const val BODY_MIN = 34.0
    private const val BODY_MAX = 43.0
}

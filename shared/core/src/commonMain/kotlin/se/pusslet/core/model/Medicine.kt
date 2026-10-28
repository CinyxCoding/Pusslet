package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One line in a child's medicine and fever log (ADR-050): a dose given or a temperature taken. Parents only; nothing but
 * what the parent writes is kept – no symptoms, no diagnosis.
 */
@Serializable
data class MedicineEntry(
    val id: String,
    val childId: String,
    val at: Instant,
    /** "Alvedon", "Kåvepenin"; empty for a temperature only. */
    val medicine: String = "",
    /** Free text as on the package: "5 ml", "250 mg", "1 tablett". */
    val dose: String = "",
    /** °C, for a temperature; null for a dose. */
    val temperature: Double? = null,
    /** The member who wrote it. */
    val by: String? = null,
    /** The course this dose belongs to, with the planned time it was for. */
    val courseId: String? = null,
    val slot: LocalDateTime? = null,
) {
    val isDose: Boolean get() = medicine.isNotBlank()
}

/**
 * A course of medicine ("kur"): [medicine] [dose] at [times] each day from [start] for [days] days ("Kåvepenin 5 ml tre
 * gånger om dagen i tio dagar"). The parents are reminded at each time until the dose is logged.
 */
@Serializable
data class MedicineCourse(
    val id: String,
    val childId: String,
    val medicine: String,
    val dose: String = "",
    /** Sorted, at most [MAX_TIMES]. */
    val times: List<LocalTime>,
    val start: LocalDate,
    val days: Int,
    val note: String = "",
    /** "Avsluta": stopped before its last day; no more reminders. */
    val stopped: Boolean = false,
    val createdBy: String? = null,
) {
    companion object {
        const val MAX_TIMES = 6
        const val MAX_DAYS = 90
    }
}

package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * A child home sick on [date] (ADR-047). One document per child and day ([idFor]), so every day has its own VAB and its
 * own absence report, and two parents reporting the same child the same morning end up with one day, not two.
 */
@Serializable
data class SickDay(
    val id: String,
    val childId: String,
    val date: LocalDate,
    /** The member who reported it. */
    val reportedBy: String? = null,
    /** Who stays home: a member id; null = not decided yet ("Vem stannar hemma?"). */
    val homeWith: String? = null,
    /** [homeWith] takes VAB (temporary parental benefit) for it; false for e.g. morfar or working from home. */
    val vab: Boolean = false,
    val vabPart: VabPart = VabPart.Whole,
    /** The school or preschool has been told ("Frånvaroanmälan"). */
    val absenceReported: Boolean = false,
    /** [homeWith] has told work. */
    val workTold: Boolean = false,
    /** The VAB day is reported to Försäkringskassan. */
    val vabReported: Boolean = false,
    /** "Frisk igen": the child is well from the next day, so nobody is asked "Fortfarande sjuk?". */
    val wellAgain: Boolean = false,
) {
    companion object {
        fun idFor(childId: String, date: LocalDate): String = "${childId}_$date"
    }
}

/** The parts of a day Försäkringskassan pays VAB for. */
@Serializable
enum class VabPart(val share: Double) {
    Whole(1.0),
    ThreeQuarters(0.75),
    Half(0.5),
    Quarter(0.25),
    Eighth(0.125),
}

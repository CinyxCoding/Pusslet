package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class Rsvp {
    @SerialName("pending")
    Pending,

    @SerialName("yes")
    Yes,

    @SerialName("no")
    No,
}

/** A present for someone else's party: the idea, what it may cost and whether it is bought. */
@Serializable
data class GiftPlan(
    val idea: String = "",
    /** Whole kronor. */
    val budget: Int? = null,
    val bought: Boolean = false,
    val boughtBy: String? = null,
)

@Serializable
data class Guest(val id: String, val name: String, val rsvp: Rsvp = Rsvp.Pending, val note: String = "")

/**
 * Party details on a "kalas" event (BRIEF §4.8, ADR-054). Either a party one of the children is invited to ([rsvp], [gift]) or
 * the family's own party ([own], [guests], [invitation]).
 */
@Serializable
data class PartyDetails(
    val own: Boolean = false,
    val rsvp: Rsvp = Rsvp.Pending,
    /** "OSA senast". */
    val rsvpBy: LocalDate? = null,
    val gift: GiftPlan = GiftPlan(),
    val guests: List<Guest> = emptyList(),
    /** Free text for the invitation (address, theme, "ingen present behövs"). */
    val invitation: String = "",
)

/** Something a child wishes for – shared with the family and other adults, so grandparents know what to give. */
@Serializable
data class WishItem(
    val id: String,
    val childId: String,
    val text: String,
    val link: String = "",
    /** Somebody has bought it (kept secret from the child's own view). */
    val taken: Boolean = false,
    val takenBy: String? = null,
    val addedAt: Instant? = null,
)

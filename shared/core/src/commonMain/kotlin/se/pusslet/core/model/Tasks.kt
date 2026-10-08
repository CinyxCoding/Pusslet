package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.pusslet.core.time.IsoWeek
import kotlin.time.Instant

@Serializable
enum class TaskKind {
    @SerialName("task")
    Task,

    /** Household chore, usually recurring and possibly rotating. */
    @SerialName("chore")
    Chore,
}

@Serializable
enum class TaskCategory {
    @SerialName("general")
    General,

    @SerialName("household")
    Household,

    @SerialName("kids")
    Kids,

    @SerialName("errand")
    Errand,

    @SerialName("gift")
    Gift,

    @SerialName("homework")
    Homework,

    @SerialName("school")
    School,
}

@Serializable
data class ChecklistItem(val id: String, val text: String, val done: Boolean = false)

/** "Dammsugning roterar veckovis": assignee for a given week is memberIds[(weeksSinceAnchor / everyWeeks) % size]. */
@Serializable
data class ChoreRotation(
    val memberIds: List<String>,
    val everyWeeks: Int = 1,
    val anchorWeek: IsoWeek,
)

@Serializable
data class Completion(val doneBy: String?, val doneAt: Instant?)

@Serializable
data class FamilyTask(
    val id: String,
    val title: String,
    val kind: TaskKind = TaskKind.Task,
    val category: TaskCategory = TaskCategory.General,
    val assigneeId: String? = null,
    val due: LocalDate? = null,
    val dueTime: LocalTime? = null,
    /** RFC 5545 RRULE for recurring tasks/chores. */
    val rrule: String? = null,
    val checklist: List<ChecklistItem> = emptyList(),
    val linkedEventId: String? = null,
    /** For non-recurring tasks. */
    val done: Boolean = false,
    val doneAt: Instant? = null,
    val doneBy: String? = null,
    /** For recurring tasks: occurrence date (ISO) -> completion. Merged per key. */
    val completions: Map<String, Completion> = emptyMap(),
    val rotation: ChoreRotation? = null,
    val sharedWithCaregivers: Boolean = false,
    val note: String = "",
    val createdBy: String? = null,
    val createdAt: Instant? = null,
)

@Serializable
enum class HandoffKind {
    @SerialName("pickup")
    PickUp,

    @SerialName("dropoff")
    DropOff,
}

@Serializable
enum class RequestStatus {
    @SerialName("pending")
    Pending,

    @SerialName("accepted")
    Accepted,

    @SerialName("declined")
    Declined,

    @SerialName("cancelled")
    Cancelled,
}

/** "Kan du ta hämtningen?" – answered with one tap (also from the notification). */
@Serializable
data class HandoffRequest(
    val id: String,
    val eventId: String,
    val occurrenceDate: LocalDate,
    val kind: HandoffKind = HandoffKind.PickUp,
    val fromMemberId: String,
    /** Null = anyone in the family. */
    val toMemberId: String? = null,
    val status: RequestStatus = RequestStatus.Pending,
    val message: String = "",
    val createdAt: Instant? = null,
    val respondedBy: String? = null,
    val respondedAt: Instant? = null,
)

/** A read-only calendar the family subscribes to (school, sports club, TimeEdit …). */
@Serializable
data class CalendarSubscription(
    val id: String,
    val name: String,
    val url: String,
    val personIds: List<String> = emptyList(),
    val color: MemberColor = MemberColor.Slate,
    val enabled: Boolean = true,
)

/** Family-wide settings (synced). Device-only preferences live in the local preferences table. */
@Serializable
data class FamilySettings(
    val weatherPlace: Place? = null,
    val mealSlots: Set<MealSlot> = setOf(MealSlot.Dinner),
    val defaultStoreId: String? = null,
    val usePantry: Boolean = false,
)

@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double)

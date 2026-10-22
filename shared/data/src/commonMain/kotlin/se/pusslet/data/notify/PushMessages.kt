package se.pusslet.data.notify

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.RequestStatus
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.notify.QuietHours
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.session.SessionProvider
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A push from another phone (ADR-042) as a notification on this one. The message carries ids (and a shopping item as it
 * was written); the names, the event's title and the time come from this phone's own data, so they are in the reader's
 * language and never travel through FCM. Null for a message about another family, an unknown kind or broken data, and
 * for a request this phone already knows is answered (or that I said no to).
 *
 * [PlannedNotification.at] is now, or the end of the quiet hours when the kind waits for them ([QuietHours]): a request
 * at 22:00 is shown at 06:30, and [planned] is asked again then, so an answer in between drops it.
 */
class PushMessages(
    private val calendar: CalendarRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
    private val prefs: PreferencesRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) {
    suspend fun planned(data: Map<String, String>): PlannedNotification? {
        withTimeoutOrNull(SESSION_WAIT) { sessions.loaded.first { it } } ?: return null
        val familyId = sessions.session.value.familyId ?: return null
        if (data["familyId"] != familyId) return null
        return when (data["type"]) {
            "request" -> request(data)
            "answer" -> answer(data)
            "shopping" -> shopping(data)
            "sick" -> sick(data)
            "pickedUp" -> pickedUp(data)
            else -> null
        }
    }

    /** "Ella är hämtad" · "Bengt · 15:52" (ADR-048); the names from this phone, the time in its zone. */
    private suspend fun pickedUp(data: Map<String, String>): PlannedNotification? {
        val names = names()
        val children = data["childIds"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { names[it] }
        if (children.isEmpty()) return null
        val at = data["at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val payload = NotificationPayload.PickedUp(children, names[data["by"]], at?.toLocalDateTime(zone)?.time)
        return planned("push:pickedup:${data["receiptId"]}", NotificationKind.Pickups, payload)
    }

    /** "Ella är sjuk idag" · "Sara sjukanmälde · Vem stannar hemma?" or "Johan stannar hemma med Ella" (ADR-047). */
    private suspend fun sick(data: Map<String, String>): PlannedNotification? {
        val date = data["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val today = clock.now().toLocalDateTime(zone).date
        if (date < today) return null
        val names = names()
        val child = names[data["childId"]] ?: return null
        val payload = NotificationPayload.Sick(
            child = child,
            date = date,
            tomorrow = date > today,
            by = names[data["by"]],
            homeWith = data["homeWith"]?.takeIf { it.isNotEmpty() }?.let { names[it] },
        )
        // One notification per child and day: who stays home replaces the first message.
        return planned("push:sick:${data["sickDayId"]}", NotificationKind.SickDay, payload)
    }

    private suspend fun request(data: Map<String, String>): PlannedNotification? {
        val about = about(data) ?: return null
        // The push may come before the request has synced here; only one this phone knows is settled is left out.
        val known = calendar.handoffRequests.first().firstOrNull { it.id == data["requestId"] }
        if (known != null && (known.status != RequestStatus.Pending || sessions.session.value.memberId in known.declines)) return null
        val payload = NotificationPayload.Request(
            eventId = about.event.id,
            occurrenceDate = about.date,
            kind = about.kind,
            from = names()[data["from"]],
            children = about.children,
            time = (if (about.kind == HandoffKind.PickUp) about.event.pickUp else about.event.dropOff)?.time
                ?: if (about.kind == HandoffKind.PickUp) about.event.end.time else about.event.start.time,
            title = about.event.title,
        )
        return planned("push:request:${data["requestId"]}", NotificationKind.Requests, payload)
    }

    private suspend fun answer(data: Map<String, String>): PlannedNotification? {
        val about = about(data) ?: return null
        val accepted = data["status"] == "accepted"
        val payload = NotificationPayload.Answer(
            eventId = about.event.id,
            occurrenceDate = about.date,
            kind = about.kind,
            by = names()[data["by"]],
            accepted = accepted,
            children = about.children,
            title = about.event.title,
        )
        return planned("push:answer:${data["requestId"]}:${data["by"]}:${data["status"]}", NotificationKind.Requests, payload)
    }

    private suspend fun shopping(data: Map<String, String>): PlannedNotification? {
        val item = data["item"]?.trim().orEmpty().ifEmpty { return null }
        // One notification per list: a second item replaces the first instead of stacking up.
        return planned(
            "push:shopping:${data["listId"]}",
            NotificationKind.Shopping,
            NotificationPayload.ShoppingAdded(names()[data["by"]], item),
        )
    }

    private class About(val event: Event, val date: LocalDate, val kind: HandoffKind, val children: List<String>)

    private suspend fun about(data: Map<String, String>): About? {
        val event = calendar.events.first().firstOrNull { it.id == data["eventId"] } ?: return null
        val date = data["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val kind = if (data["kind"] == "dropoff") HandoffKind.DropOff else HandoffKind.PickUp
        val children = family.children.first().filter { it.id in event.participantIds }.map { it.firstName }
        return About(event, date, kind, children)
    }

    private suspend fun names(): Map<String, String> =
        family.members.first().map(FamilyPerson::of).associate { it.id to it.name } +
            family.children.first().associate { it.id to it.firstName }

    private suspend fun planned(key: String, kind: NotificationKind, payload: NotificationPayload): PlannedNotification? {
        val now = clock.now()
        val at = QuietHours.adjust(now, prefs.get(Prefs.QuietHours), prefs.get(Prefs.QuietHoursEnabled), kind, zone) ?: return null
        return PlannedNotification(key, at, kind, payload)
    }

    private companion object {
        /** Within the few seconds FCM gives a message (the app may just have been started for it). */
        val SESSION_WAIT = 5.seconds
    }
}

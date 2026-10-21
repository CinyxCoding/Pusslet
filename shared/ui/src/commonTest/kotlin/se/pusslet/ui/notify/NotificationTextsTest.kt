package se.pusslet.ui.notify

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.BriefEvent
import se.pusslet.core.notify.BriefHandoff
import se.pusslet.core.notify.BriefPacking
import se.pusslet.core.notify.BriefStop
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.PlannedNotification
import se.pusslet.core.today.Greeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The sentences of the notifications, with the Swedish strings (as in values/strings.xml) put in through the lookup, so the
 * test runs without Android resources (host tests have no asset context).
 */
class NotificationTextsTest {
    private val sv = mapOf(
        "list_separator" to ", ",
        "and_separator" to " och ",
        "time_range" to "%1\$s–%2\$s",
        "greeting_morning" to "God morgon",
        "greeting_day" to "Hej",
        "notify_event_who" to "%1\$s %2\$s",
        "notify_event_now" to "%1\$s börjar nu",
        "notify_event_in_minutes" to "%1\$s om %2\$d min",
        "notify_event_in_hours" to "%1\$s om %2\$d tim",
        "notify_event_tomorrow_at" to "%1\$s imorgon %2\$s",
        "notify_event_in_days" to "%1\$s om %2\$d dagar",
        "notify_event_allday_tomorrow" to "%1\$s imorgon",
        "notify_you_dropoff" to "Du lämnar %1\$s",
        "notify_you_pickup" to "Du hämtar %1\$s",
        "notify_stop" to "%1\$s %2\$s",
        "notify_task_today" to "Att göra idag",
        "notify_task_today_at" to "Att göra idag %1\$s",
        "notify_brief_person_dropoff" to "%1\$s lämnar %2\$s",
        "notify_brief_person_pickup" to "%1\$s hämtar %2\$s",
        "notify_brief_you_dropoff" to "du lämnar %1\$s",
        "notify_brief_you_pickup" to "du hämtar %1\$s",
        "notify_brief_nobody_pickup" to "hämtning %1\$s",
        "notify_brief_sentence" to "%1\$s.",
        "notify_brief_packing" to "Ska med: %1\$s.",
        "notify_brief_first" to "Först idag: %1\$s.",
        "notify_brief_empty" to "Inget inbokat idag.",
        "notify_item_for" to "%1\$s (%2\$s)",
        "notify_evening_title" to "Kvällskoll",
        "notify_evening_body" to "Packa ikväll: %1\$s",
        "notify_bundle_reminders" to "%1\$d påminnelser",
        "notify_bundle_handoffs" to "%1\$d lämningar och hämtningar",
        "notify_channel_handoffs" to "Lämna och hämta",
        "notify_channel_handoffs_desc" to "30 min innan du lämnar eller hämtar",
        "notify_request_pickup" to "Kan du hämta %1\$s?",
        "notify_request_dropoff" to "Kan du lämna %1\$s?",
        "notify_request_from" to "%1\$s frågar",
        "notify_answer_yes_pickup" to "%1\$s hämtar %2\$s",
        "notify_answer_no" to "%1\$s kan inte",
        "notify_someone" to "Någon",
        "notify_shopping_added" to "%1\$s lade till %2\$s",
        "notify_shopping_added_body" to "På listan du handlar från",
    )

    private val texts = NotificationTexts { res, args ->
        val template = sv[res.key] ?: error("No Swedish string for ${res.key} in the test")
        Regex("""%(\d)\$[sd]""").replace(template) { m -> args[m.groupValues[1].toInt() - 1].toString() }
    }

    private val day = LocalDate(2026, 10, 21)
    private val at = Instant.parse("2026-10-21T12:00:00Z")

    private fun n(payload: NotificationPayload, kind: NotificationKind = NotificationKind.Reminder) = PlannedNotification(
        "k",
        at,
        kind,
        payload,
    )

    private fun dentist(
        minutes: Int,
        children: List<String> = listOf(
            "Simon",
        ),
        allDay: Boolean = false,
    ) = NotificationPayload.EventReminder(
        eventId = "e-dent",
        occurrenceDate = day,
        title = "Tandläkare",
        start = LocalDateTime(day, LocalTime(14, 30)),
        end = LocalDateTime(day, LocalTime(15, 0)),
        allDay = allDay,
        minutesBefore = minutes,
        location = "Folktandvården",
        children = children,
    )

    @Test
    fun eventReminders() = runTest {
        assertEquals(NotificationText("Tandläkare Simon om 30 min", "14:30–15:00 · Folktandvården"), texts.text(n(dentist(30))))
        assertEquals("Tandläkare om 2 tim", texts.text(n(dentist(120, children = emptyList()))).title)
        assertEquals("Tandläkare Simon imorgon 14:30", texts.text(n(dentist(1440))).title)
        assertEquals("Tandläkare Simon börjar nu", texts.text(n(dentist(0))).title)
        assertEquals("Tandläkare Simon om 90 min", texts.text(n(dentist(90))).title)
        assertEquals("Tandläkare Simon och Ella imorgon", texts.text(n(dentist(0, listOf("Simon", "Ella"), allDay = true))).title)
    }

    @Test
    fun handoffSoon() = runTest {
        val p = NotificationPayload.Handoff("e-school", day, HandoffKind.PickUp, LocalTime(15, 45), listOf("Ella"), "Björkängsskolan")
        val text = texts.text(n(p, NotificationKind.HandoffSoon))
        assertEquals(NotificationText("Du hämtar Ella 15:45", "Björkängsskolan"), text)
        assertEquals("Du hämtar Ella 15:45 · Björkängsskolan", text.oneLine)
        val both = p.copy(kind = HandoffKind.DropOff, time = LocalTime(8, 5), children = listOf("Ella", "Simon"))
        assertEquals("Du lämnar Ella och Simon 08:05", texts.text(n(both, NotificationKind.HandoffSoon)).title)
    }

    @Test
    fun toDos() = runTest {
        assertEquals(
            NotificationText("Köp present", "Att göra idag 18:00"),
            texts.text(n(NotificationPayload.Task("t", day, "Köp present", LocalTime(18, 0)))),
        )
        assertEquals("Att göra idag", texts.text(n(NotificationPayload.Task("t", day, "Köp present", null))).body)
    }

    @Test
    fun theMorningBriefAsInTheBrief() = runTest {
        val p = NotificationPayload.Morning(
            date = day,
            greeting = Greeting.Morning,
            handoffs = listOf(
                BriefHandoff(HandoffKind.DropOff, "Johan", isMe = false, listOf(BriefStop("Simon", LocalTime(7, 50)))),
                BriefHandoff(HandoffKind.PickUp, "Sara", isMe = true, listOf(BriefStop("Simon", LocalTime(15, 30)))),
            ),
            packing = listOf(BriefPacking("Ella", listOf("Gympapåse"))),
        )
        val text = texts.text(n(p, NotificationKind.Morning))
        assertEquals("God morgon", text.title)
        assertEquals("Johan lämnar Simon 07:50, du hämtar 15:30. Ska med: gympapåse (Ella).", text.body)
    }

    @Test
    fun morningBriefsWithOtherDays() = runTest {
        val me = BriefHandoff(
            HandoffKind.PickUp,
            "Sara",
            isMe = true,
            listOf(BriefStop("Simon", LocalTime(15, 30)), BriefStop("Ella", LocalTime(15, 30)), BriefStop("Vera", LocalTime(16, 0))),
        )
        val day1 = NotificationPayload.Morning(day, Greeting.Morning, listOf(me), emptyList())
        assertEquals("Du hämtar Simon och Ella 15:30 och Vera 16:00.", texts.text(n(day1, NotificationKind.Morning)).body)
        val nobody = me.copy(person = null, isMe = false, stops = me.stops.take(1))
        assertEquals("Hämtning Simon 15:30.", texts.text(n(day1.copy(handoffs = listOf(nobody)), NotificationKind.Morning)).body)
        val quiet = NotificationPayload.Morning(day, Greeting.Day, emptyList(), emptyList(), BriefEvent("Simskola", LocalTime(10, 0)))
        assertEquals(NotificationText("Hej", "Först idag: Simskola 10:00."), texts.text(n(quiet, NotificationKind.Morning)))
        assertEquals("Inget inbokat idag.", texts.text(n(quiet.copy(first = null), NotificationKind.Morning)).body)
    }

    @Test
    fun kvallskoll() = runTest {
        val p = NotificationPayload.Evening(
            day,
            listOf(BriefPacking("Ella", listOf("Gympapåse", "Handduk")), BriefPacking("Simon", listOf("Regnkläder"))),
        )
        assertEquals(
            NotificationText("Kvällskoll", "Packa ikväll: gympapåse, handduk (Ella), regnkläder (Simon)"),
            texts.text(n(p, NotificationKind.Evening)),
        )
    }

    @Test
    fun aSummary() = runTest {
        val items = listOf(
            n(NotificationPayload.Task("a", day, "Köp present", null)),
            n(NotificationPayload.Task("b", day, "Ring skolan", null)),
            n(dentist(30)),
        )
        val text = texts.text(n(NotificationPayload.Bundle(NotificationKind.Reminder, items)))
        assertEquals("3 påminnelser", text.title)
        assertEquals(
            listOf(
                "Köp present · Att göra idag",
                "Ring skolan · Att göra idag",
                "Tandläkare Simon om 30 min · 14:30–15:00 · Folktandvården",
            ),
            text.lines,
        )
    }

    @Test
    fun pushFromAnotherPhone() = runTest {
        val ask = NotificationPayload.Request("e", day, HandoffKind.PickUp, "Sara", listOf("Ella", "Simon"), LocalTime(15, 45), "Skola")
        assertEquals(NotificationText("Kan du hämta Ella och Simon?", "Sara frågar · 15:45"), texts.text(n(ask, NotificationKind.Requests)))
        val unknown = ask.copy(kind = HandoffKind.DropOff, from = null, children = emptyList(), time = null)
        assertEquals(NotificationText("Kan du lämna Skola?", ""), texts.text(n(unknown, NotificationKind.Requests)))

        val yes = NotificationPayload.Answer("e", day, HandoffKind.PickUp, "Bengt", true, listOf("Ella"), "Skola")
        assertEquals(NotificationText("Bengt hämtar Ella", "Skola"), texts.text(n(yes, NotificationKind.Requests)))
        assertEquals(
            NotificationText("Någon kan inte", "Skola"),
            texts.text(n(yes.copy(by = null, accepted = false), NotificationKind.Requests)),
        )

        val item = NotificationPayload.ShoppingAdded("Johan", "mjölk")
        assertEquals(NotificationText("Johan lade till mjölk", "På listan du handlar från"), texts.text(n(item, NotificationKind.Shopping)))
    }

    @Test
    fun channels() = runTest {
        assertEquals(ChannelText("Lämna och hämta", "30 min innan du lämnar eller hämtar"), texts.channel(NotificationKind.HandoffSoon))
    }
}

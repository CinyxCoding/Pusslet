package se.pusslet.ui.widget

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.notify.BriefHandoff
import se.pusslet.core.notify.BriefPacking
import se.pusslet.core.notify.BriefStop
import se.pusslet.core.widget.GlanceEvent
import se.pusslet.core.widget.GlanceItem
import se.pusslet.core.widget.ShoppingGlance
import se.pusslet.core.widget.TodayGlance
import se.pusslet.ui.notify.NotificationTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The widgets' words with the Swedish strings (as in values/strings.xml), put in through the lookups as in NotificationTextsTest. */
class WidgetTextsTest {
    private val sv = mapOf(
        "list_separator" to ", ",
        "and_separator" to " och ",
        "date_long" to "%1\$s %2\$d %3\$s",
        "all_day" to "Heldag",
        "calendar_more_events" to "+%1\$d till",
        "notify_item_for" to "%1\$s (%2\$s)",
        "notify_stop" to "%1\$s %2\$s",
        "notify_brief_you_pickup" to "du hämtar %1\$s",
        "notify_brief_person_dropoff" to "%1\$s lämnar %2\$s",
        "today_packing_line" to "Ska med: %1\$s",
        "today_packing_done" to "Allt är packat",
        "today_quiet_end" to "Inget mer idag",
        "widget_no_family" to "Öppna Pusslet och kom igång",
        "widget_problems" to "Att lösa: %1\$d",
        "today_block_shopping" to "Inköpslistan",
        "shopping_left_count" to "%1\$d kvar",
        "shopping_empty_title" to "Listan är tom",
        "shopping_add_action" to "Lägg till på listan",
    )
    private val weekdays = listOf("måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag")
    private val months = listOf(
        "januari",
        "februari",
        "mars",
        "april",
        "maj",
        "juni",
        "juli",
        "augusti",
        "september",
        "oktober",
        "november",
        "december",
    )

    private val texts = WidgetTexts(
        lookup = NotificationTexts.StringLookup { res, args ->
            val template = sv[res.key] ?: error("No Swedish string for ${res.key} in the test")
            Regex("""%(\d)\$[sd]""").replace(template) { m -> args[m.groupValues[1].toInt() - 1].toString() }
        },
        arrays = WidgetTexts.ArrayLookup { res -> if (res.key == "weekdays") weekdays else months },
    )

    private val wednesday = LocalDate(2026, 10, 21)

    @Test
    fun idagSaysItAsMorgonkollen() = runTest {
        val glance = TodayGlance(
            date = wednesday,
            next = GlanceEvent("e-dans", wednesday, "Dans", LocalTime(17, 0), listOf("Ella")),
            later = 2,
            handoffs = listOf(
                BriefHandoff(HandoffKind.DropOff, "Johan", isMe = false, stops = listOf(BriefStop("Simon", LocalTime(7, 50)))),
                BriefHandoff(HandoffKind.PickUp, "Sara", isMe = true, stops = listOf(BriefStop("Ella", LocalTime(15, 45)))),
            ),
            packing = listOf(BriefPacking("Ella", listOf("Gympapåse"))),
            problems = 1,
        )
        val text = texts.today(glance, wednesday)
        assertEquals("Onsdag 21 oktober", text.date)
        assertEquals("17:00 Dans (Ella)", text.next)
        assertEquals("+2 till", text.later)
        assertEquals(listOf("Johan lämnar Simon 07:50", "Du hämtar Ella 15:45"), text.handoffs)
        assertEquals("Ska med: gympapåse (Ella)", text.packing)
        assertEquals("Att lösa: 1", text.problems)
        assertNull(text.empty)
    }

    @Test
    fun allDayPackedEmptyAndNoFamily() = runTest {
        val allDay = texts.today(
            TodayGlance(wednesday, next = GlanceEvent("e-lov", wednesday, "Höstlov", null, emptyList()), allPacked = true),
            wednesday,
        )
        assertEquals("Heldag · Höstlov", allDay.next)
        assertEquals("Allt är packat", allDay.packing)
        assertNull(allDay.later)

        assertEquals("Inget mer idag", texts.today(TodayGlance(wednesday), wednesday).empty)
        val noFamily = texts.today(null, wednesday)
        assertEquals("Onsdag 21 oktober", noFamily.date)
        assertEquals("Öppna Pusslet och kom igång", noFamily.empty)
    }

    @Test
    fun theShoppingListAndItsEmptyStates() = runTest {
        val list = texts.shopping(ShoppingGlance("l", "Veckohandling", listOf(GlanceItem("i-1", "2 l mjölk"), GlanceItem("i-2", "bröd"))))
        assertEquals("Veckohandling", list.title)
        assertEquals("2 kvar", list.left)
        assertNull(list.empty)
        assertEquals("Lägg till på listan", list.add)

        val empty = texts.shopping(ShoppingGlance("", "", emptyList()))
        assertEquals("Inköpslistan", empty.title, "no list yet")
        assertEquals("Listan är tom", empty.empty)
        assertNull(empty.left)
        assertEquals("Öppna Pusslet och kom igång", texts.shopping(null).empty)
    }
}

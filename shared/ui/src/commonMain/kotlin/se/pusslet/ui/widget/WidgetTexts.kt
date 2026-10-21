package se.pusslet.ui.widget

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getStringArray
import se.pusslet.core.widget.ShoppingGlance
import se.pusslet.core.widget.TodayGlance
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.notify.NotificationTexts
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.all_day
import se.pusslet.ui.resources.calendar_more_events
import se.pusslet.ui.resources.date_long
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.months
import se.pusslet.ui.resources.notify_item_for
import se.pusslet.ui.resources.shopping_add_action
import se.pusslet.ui.resources.shopping_empty_title
import se.pusslet.ui.resources.shopping_left_count
import se.pusslet.ui.resources.today_block_shopping
import se.pusslet.ui.resources.today_packing_done
import se.pusslet.ui.resources.today_packing_line
import se.pusslet.ui.resources.today_quiet_end
import se.pusslet.ui.resources.weekdays
import se.pusslet.ui.resources.widget_no_family
import se.pusslet.ui.resources.widget_problems

/** The Idag widget in words. Null parts are not shown; [empty] stands alone when there is nothing else. */
data class TodayWidgetText(
    /** "Onsdag 21 oktober" */
    val date: String,
    /** "15:15 Dans (Ella)" or "Heldag · Lov" */
    val next: String? = null,
    /** "+2 till" */
    val later: String? = null,
    /** "Du hämtar Ella 15:45", one per line. */
    val handoffs: List<String> = emptyList(),
    /** "Ska med: gympapåse (Ella)" or "Allt är packat" */
    val packing: String? = null,
    /** "Att lösa: 1" */
    val problems: String? = null,
    /** "Inget mer idag", or how to get started without a family. */
    val empty: String? = null,
)

/** The Inköpslistan widget in words; the items are written as the list writes them. */
data class ShoppingWidgetText(
    val title: String,
    /** "3 kvar"; null when nothing is left. */
    val left: String?,
    /** "Listan är tom", or how to get started without a family; null when there is something to buy. */
    val empty: String?,
    /** The + button, for TalkBack. */
    val add: String,
)

/**
 * Texts for the home-screen widgets (ADR-039) from the string resources, outside composition, because Glance draws remote
 * views rather than Compose UI. Hand-offs and packing are worded by [NotificationTexts], as in Morgonkollen. Tests pass
 * their own lookups, as for [NotificationTexts].
 */
class WidgetTexts(
    private val lookup: NotificationTexts.StringLookup = NotificationTexts.ComposeStrings,
    private val arrays: ArrayLookup = ComposeArrays,
) {
    /** Reads one string array resource (weekdays, months). */
    fun interface ArrayLookup {
        suspend fun get(res: StringArrayResource): List<String>
    }

    object ComposeArrays : ArrayLookup {
        override suspend fun get(res: StringArrayResource): List<String> = getStringArray(res)
    }

    private val brief = NotificationTexts(lookup)

    private suspend fun str(res: StringResource, vararg args: Any): String = lookup.get(res, args.toList())

    /** [glance] is null without a family on this phone; [date] is today, for the heading. */
    suspend fun today(glance: TodayGlance?, date: LocalDate): TodayWidgetText {
        val heading = date(date)
        if (glance == null) return TodayWidgetText(heading, empty = str(Res.string.widget_no_family))
        val next = glance.next?.let { e ->
            val names = e.names.joinToString(str(Res.string.list_separator))
            val what = if (e.names.isEmpty()) e.title else str(Res.string.notify_item_for, e.title, names)
            val time = e.time
            if (time == null) str(Res.string.all_day) + SEPARATOR + what else formatTime(time) + " " + what
        }
        val packing = when {
            glance.packing.isNotEmpty() -> str(Res.string.today_packing_line, brief.packingList(glance.packing))
            glance.allPacked -> str(Res.string.today_packing_done)
            else -> null
        }
        return TodayWidgetText(
            date = heading,
            next = next,
            later = glance.later.takeIf { it > 0 }?.let { str(Res.string.calendar_more_events, it) },
            handoffs = glance.handoffs.map { brief.handoffLine(it) },
            packing = packing,
            problems = glance.problems.takeIf { it > 0 }?.let { str(Res.string.widget_problems, it) },
            empty = if (glance.isEmpty) str(Res.string.today_quiet_end) else null,
        )
    }

    /** [glance] is null without a family on this phone. */
    suspend fun shopping(glance: ShoppingGlance?): ShoppingWidgetText {
        val title = glance?.listName?.ifBlank { null } ?: str(Res.string.today_block_shopping)
        val left = glance?.items?.size ?: 0
        return ShoppingWidgetText(
            title = title,
            left = left.takeIf { it > 0 }?.let { str(Res.string.shopping_left_count, it) },
            empty = when {
                glance == null -> str(Res.string.widget_no_family)
                left == 0 -> str(Res.string.shopping_empty_title)
                else -> null
            },
            add = str(Res.string.shopping_add_action),
        )
    }

    /** "Onsdag 21 oktober", as Idag's heading. */
    private suspend fun date(date: LocalDate): String {
        val weekday = arrays.get(Res.array.weekdays)[date.dayOfWeek.isoDayNumber - 1]
        val month = arrays.get(Res.array.months)[date.month.ordinal]
        return str(Res.string.date_long, weekday, date.day, month).replaceFirstChar { it.uppercase() }
    }

    private companion object {
        /** Between the parts of one line, as on Idag. */
        const val SEPARATOR = " · "
    }
}

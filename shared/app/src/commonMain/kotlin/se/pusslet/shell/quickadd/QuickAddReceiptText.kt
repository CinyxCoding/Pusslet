package se.pusslet.shell.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.getString
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.data.usecase.QuickAddReceipt
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.resources.*

/** What the snackbar after quick add offers next to its text. */
internal enum class ReceiptAction { Undo, Show }

internal data class ReceiptMessage(val text: String, val action: ReceiptAction?)

/** What the receipt text needs from the UI: today, short weekday and month names, and the family for names. */
internal class ReceiptContext(
    val today: LocalDate,
    val weekdaysShort: List<String>,
    val monthsShort: List<String>,
    val people: List<FamilyPerson>,
)

/**
 * Says what happened and where (UX plan 1.8): "2 varor på listan · Visa", "I kalendern lör 14:00 · Ångra",
 * "Johan hämtar fre" (no Ångra: a changed hand-off is not undone).
 */
internal suspend fun receiptMessage(receipt: QuickAddReceipt, ctx: ReceiptContext): ReceiptMessage {
    val action = when {
        receipt is QuickAddReceipt.Items -> ReceiptAction.Show
        receipt.undo -> ReceiptAction.Undo
        else -> null
    }
    val text = when (receipt) {
        is QuickAddReceipt.Items -> if (receipt.count == 1) {
            getString(Res.string.quickadd_saved_item)
        } else {
            getString(Res.string.quickadd_saved_items, receipt.count)
        }
        is QuickAddReceipt.InCalendar -> getString(Res.string.quickadd_saved_event, ctx.day(receipt.slot.date, receipt.slot.time))
        is QuickAddReceipt.OnMenu -> getString(Res.string.quickadd_saved_meal, ctx.day(receipt.date, null))
        QuickAddReceipt.TaskSaved -> getString(Res.string.quickadd_saved_task)
        is QuickAddReceipt.HandoffChanged -> {
            val handoff = receipt.handoff
            val name = ctx.people.firstOrNull { it.id == handoff.personId }?.name
            when {
                name == null -> getString(Res.string.quickadd_saved)
                handoff.kind == HandoffKind.PickUp -> getString(Res.string.quickadd_saved_pickup, name, ctx.day(handoff.date, handoff.time))
                else -> getString(Res.string.quickadd_saved_dropoff, name, ctx.day(handoff.date, handoff.time))
            }
        }
        is QuickAddReceipt.HandoffCreated -> {
            val handoff = receipt.handoff
            val time = handoff.time
            when {
                time == null -> getString(Res.string.quickadd_saved)
                handoff.kind == HandoffKind.PickUp -> getString(Res.string.quickadd_no_handoff_found, formatTime(time))
                else -> getString(Res.string.quickadd_no_dropoff_found, formatTime(time))
            }
        }
        is QuickAddReceipt.Saved -> getString(Res.string.quickadd_saved)
    }
    return ReceiptMessage(text, action)
}

/** "lör" within the coming week, else "17 okt"; with a time "lör 14:00". */
private suspend fun ReceiptContext.day(date: LocalDate, time: LocalTime?): String {
    val days = date.toEpochDays() - today.toEpochDays()
    val day = if (days in 0..6) {
        weekdaysShort[date.dayOfWeek.isoDayNumber - 1]
    } else {
        getString(Res.string.date_short, date.day, monthsShort[date.month.ordinal])
    }
    return if (time == null) day else getString(Res.string.quickadd_day_time, day, formatTime(time))
}

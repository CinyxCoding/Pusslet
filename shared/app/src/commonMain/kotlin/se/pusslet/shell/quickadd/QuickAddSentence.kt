package se.pusslet.shell.quickadd

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.quickadd.ActionKind
import se.pusslet.core.quickadd.QuickAddAction
import se.pusslet.core.quickadd.UncertainField
import se.pusslet.core.units.Units
import se.pusslet.ui.format.formatTime

/** One piece of the preview sentence. [uncertain] marks what the parser guessed; the sheet draws it in terracotta. */
data class SentencePart(val text: String, val uncertain: Boolean = false)

/**
 * The words the preview sentence is made of, already in the UI language (the sheet reads them from the string resources).
 * Templates use `%1$s`-style placeholders like the resources they come from.
 */
class SentenceWords(
    /** "%1$s har %2$s": who, then what. */
    val who: String,
    /** "%1$s: %2$s": a kind, then what ("Till inköpslistan: Mjölk"). */
    val labeled: String,
    /** "senast %1$s". */
    val due: String,
    /** "%1$s hämtar %2$s". */
    val pickUp: String,
    /** "%1$s lämnar %2$s". */
    val dropOff: String,
    /** "%1$s–%2$s", for times and days. */
    val range: String,
    /** "barnen", when a hand-off names no child. */
    val allChildren: String,
    val today: String,
    val tomorrow: String,
    /** Monday first. */
    val weekdays: List<String>,
    val monthsShort: List<String>,
    /** "%1$d %2$s" ("17 okt"). */
    val dateShort: String,
    val listSeparator: String,
    val andSeparator: String,
    val kinds: Map<ActionKind, String>,
    val mealSlots: Map<MealSlot, String>,
)

/**
 * Turns one parsed action into the one-line preview (UX plan 4.6): "Ella har dans tisdag 17:00–18:00", "Till inköpslistan:
 * Mjölk", "Alva hämtar Maja fredag 15:30". Each guessed field ([UncertainField]) is its own part, so the sheet can mark it.
 * Pure: unit tested with fixed words.
 */
internal object QuickAddSentence {
    /**
     * [uncertain] are the parser's guesses for this action (only the first action has any). [repeat] is the sheet's text for
     * the action's repeat rule ("Varje tisdag"), which replaces the day.
     */
    fun build(
        action: QuickAddAction,
        uncertain: Set<UncertainField>,
        input: String,
        people: List<FamilyPerson>,
        today: LocalDate,
        words: SentenceWords,
        repeat: String? = null,
    ): List<SentencePart> {
        val b = Builder(words, uncertain)
        val parts = when (action) {
            is QuickAddAction.CreateEvent -> b.event(action, input, people, today, repeat)
            is QuickAddAction.CreateTask -> b.task(action, people, today)
            is QuickAddAction.AddShoppingItems -> b.labeled(ActionKind.Shopping, b.items(action))
            is QuickAddAction.PlanMeal -> b.meal(action, today)
            is QuickAddAction.AssignHandoff -> b.handoff(action, people, today, repeat)
        }
        return normalize(parts)
    }

    /** Fills `%1$s`/`%1$d` placeholders with parts; the template's own words are never uncertain. */
    fun fill(template: String, vararg args: List<SentencePart>): List<SentencePart> {
        val out = mutableListOf<SentencePart>()
        var last = 0
        for (m in PLACEHOLDER.findAll(template)) {
            if (m.range.first > last) out += SentencePart(template.substring(last, m.range.first))
            out += args.getOrNull(m.groupValues[1].toInt() - 1).orEmpty()
            last = m.range.last + 1
        }
        if (last < template.length) out += SentencePart(template.substring(last))
        return out
    }

    /** Joins neighbours with the same marking and drops doubled spaces left by empty pieces. */
    fun normalize(parts: List<SentencePart>): List<SentencePart> {
        val merged = mutableListOf<SentencePart>()
        for (p in parts) {
            if (p.text.isEmpty()) continue
            val prev = merged.lastOrNull()
            if (prev != null && prev.uncertain == p.uncertain) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + p.text)
            } else {
                merged += p
            }
        }
        val spaced = merged.map { it.copy(text = it.text.replace(SPACES, " ")) }.toMutableList()
        for (i in 1 until spaced.size) {
            if (spaced[i - 1].text.endsWith(' ') && spaced[i].text.startsWith(' ')) {
                spaced[i] = spaced[i].copy(text = spaced[i].text.drop(1))
            }
        }
        val kept = spaced.filter { it.text.isNotEmpty() }.toMutableList()
        if (kept.isNotEmpty()) {
            kept[0] = kept[0].copy(text = kept[0].text.trimStart())
            kept[kept.lastIndex] = kept.last().copy(text = kept.last().text.trimEnd())
        }
        return kept.filter { it.text.isNotEmpty() }
    }

    /**
     * "Idag" -> "idag", "Varje tisdag" -> "varje tisdag" inside a sentence; "IKEA" stays. Only for the app's own words, never
     * for names or typed titles.
     */
    fun inSentence(word: String): String =
        if (word.length >= 2 && word[0].isUpperCase() && word[1].isLowerCase()) word.replaceFirstChar { it.lowercaseChar() } else word

    private class Builder(val words: SentenceWords, val uncertain: Set<UncertainField>) {
        fun part(text: String, field: UncertainField? = null) = listOf(SentencePart(text, field != null && field in uncertain))

        fun space() = listOf(SentencePart(" "))

        fun names(names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names.first()
            else -> names.dropLast(1).joinToString(words.listSeparator) + words.andSeparator + names.last()
        }

        fun day(date: LocalDate, today: LocalDate): String {
            val days = date.toEpochDays() - today.toEpochDays()
            return when {
                days == 0L -> inSentence(words.today)
                days == 1L -> inSentence(words.tomorrow)
                days in 2..6 -> words.weekdays[date.dayOfWeek.isoDayNumber - 1]
                else -> fill(words.dateShort, part(date.day.toString()), part(words.monthsShort[date.month.ordinal]))
                    .joinToString("") { it.text }
            }
        }

        /** "Ella har dans tisdag 17:00–18:00", "Tandläkare imorgon 09:00–10:00", "Simon har simskola varje lördag 10:00–11:00". */
        fun event(
            e: QuickAddAction.CreateEvent,
            input: String,
            people: List<FamilyPerson>,
            today: LocalDate,
            repeat: String?,
        ): List<SentencePart> {
            val who = names(e.participantIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.name })
            val titleText = if (who.isNotEmpty()) titleInSentence(e.title, input) else e.title
            val title = part(titleText, UncertainField.Title)
            val head = if (who.isEmpty()) title else fill(words.who, part(who, UncertainField.Person), title)
            val start = e.startTime
            val end = e.endTime
            val time = when {
                start == null -> emptyList()
                end == null -> part(formatTime(start), UncertainField.Time)
                else -> fill(words.range, part(formatTime(start), UncertainField.Time), part(formatTime(end), UncertainField.EndTime))
            }
            val location = if (e.location.isBlank()) emptyList() else part(words.listSeparator) + part(e.location)
            val sentence = head + space() + whenPart(e.date, e.endDate, today, repeat) + space() + time + location
            return if (UncertainField.Kind in uncertain) labeled(ActionKind.Event, sentence) else sentence
        }

        /** "Att göra: Köp present senast fredag · Johan". */
        fun task(t: QuickAddAction.CreateTask, people: List<FamilyPerson>, today: LocalDate): List<SentencePart> {
            val due = t.due?.let { space() + fill(words.due, part(day(it, today), UncertainField.Date)) }.orEmpty()
            val assignee = t.assigneeId?.let { id -> people.firstOrNull { it.id == id }?.name }
                ?.let { part(SEPARATOR) + part(it, UncertainField.Person) }.orEmpty()
            return labeled(ActionKind.Task, part(t.title, UncertainField.Title) + due + assignee)
        }

        fun items(s: QuickAddAction.AddShoppingItems): List<SentencePart> = part(
            s.items.joinToString(words.listSeparator) { item -> item.quantity?.let { Units.format(it) + " " }.orEmpty() + item.name },
        )

        /** "Till matsedeln: Tacos fredag", with the meal only when it is not dinner. */
        fun meal(m: QuickAddAction.PlanMeal, today: LocalDate): List<SentencePart> {
            val slot = if (m.slot == MealSlot.Dinner) {
                emptyList()
            } else {
                part(words.listSeparator) + part(inSentence(words.mealSlots[m.slot].orEmpty()))
            }
            val dayPart = part(day(m.date, today), UncertainField.Date)
            return labeled(ActionKind.Meal, part(m.title, UncertainField.Title) + space() + dayPart + slot)
        }

        /** "Alva hämtar Maja fredag 15:30", "Johan lämnar barnen varje måndag". */
        fun handoff(h: QuickAddAction.AssignHandoff, people: List<FamilyPerson>, today: LocalDate, repeat: String?): List<SentencePart> {
            val person = people.firstOrNull { it.id == h.personId }?.name.orEmpty()
            val children = names(h.childIds.mapNotNull { id -> people.firstOrNull { it.id == id }?.name }).ifEmpty { words.allChildren }
            val template = if (h.kind == HandoffKind.PickUp) words.pickUp else words.dropOff
            val time = h.time?.let { space() + part(formatTime(it), UncertainField.Time) }.orEmpty()
            val dayPart = if (h.recurringDays.isNotEmpty() && repeat != null) {
                part(inSentence(repeat))
            } else {
                part(day(h.date, today), UncertainField.Date)
            }
            return fill(template, part(person, UncertainField.Person), part(children)) + space() + dayPart + time
        }

        fun labeled(kind: ActionKind, rest: List<SentencePart>): List<SentencePart> =
            fill(words.labeled, part(words.kinds[kind].orEmpty(), UncertainField.Kind), rest)

        /** The repeat rule replaces the day ("varje tisdag"); a multi-day event shows its first and last day. */
        private fun whenPart(date: LocalDate, endDate: LocalDate?, today: LocalDate, repeat: String?): List<SentencePart> {
            if (repeat != null) {
                return listOf(SentencePart(inSentence(repeat), UncertainField.Recurrence in uncertain || UncertainField.Date in uncertain))
            }
            val first = part(day(date, today), UncertainField.Date)
            if (endDate == null || endDate == date) return first
            return fill(words.range, first, part(day(endDate, today), UncertainField.Date))
        }

        /** "Dans" reads "dans" after a name when its first word was typed that way ("Ella dans tis 17"); "IKEA" stays. */
        private fun titleInSentence(title: String, input: String): String {
            val first = title.substringBefore(' ')
            val lower = first.replaceFirstChar { it.lowercaseChar() }
            return if (lower != first && lower in input.split(WORD_BREAK)) title.replaceFirstChar { it.lowercaseChar() } else title
        }
    }

    private val PLACEHOLDER = Regex("""%(\d+)\$[sd]""")
    private val SPACES = Regex(""" {2,}""")
    private val WORD_BREAK = Regex("""[\s,.;:!?()"]+""")

    /** Language-neutral, like the calendar's rows. */
    private const val SEPARATOR = " · "
}

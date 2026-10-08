package se.pusslet.core.quickadd

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.recurrence.RRule
import se.pusslet.core.recurrence.RecurrenceExpander
import se.pusslet.core.recurrence.WeekdayNum
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.core.units.QuantityParser

/**
 * Rule-based Swedish natural-language parser for the global "+" field. No AI (ADR-001): just patterns and word lists,
 * so behaviour is predictable, offline and testable. Anything ambiguous is reported in [QuickAddResult.uncertain]
 * so the preview can highlight it.
 *
 * Examples: "Simon fotboll tis 17:30 varje vecka", "Ella kalas lör 14-16 hos Vera, köp present",
 * "mjölk, 2 gurka, pasta", "Johan hämtar imorgon", "tacos fredag".
 */
class SwedishQuickAddParser {
    fun parse(input: String, ctx: QuickAddContext): QuickAddResult {
        // Keep newlines: a pasted list with one item per line is a shopping list.
        val text = input.trim().replace(Regex("""[ \t]+"""), " ").replace(Regex(""" ?\r?\n ?"""), "\n")
        if (text.isEmpty()) return QuickAddResult(input, emptyList())

        shoppingList(text)?.let { return QuickAddResult(input, listOf(QuickAddAction.AddShoppingItems(it))) }

        val clauses = splitClauses(text)
        val uncertain = mutableSetOf<UncertainField>()
        val main = parseClause(clauses.first(), ctx, uncertain)
        val actions = mutableListOf(main)

        for (extra in clauses.drop(1)) {
            val lower = extra.lowercase()
            val packing = PACKING_PREFIX.find(lower)
            when {
                main is QuickAddAction.CreateEvent && packing != null -> {
                    val item = capitalize(extra.substring(packing.range.last + 1).trim())
                    if (item.isNotEmpty()) actions[0] = (actions[0] as QuickAddAction.CreateEvent).let { it.copy(packingItems = it.packingItems + item) }
                }
                main is QuickAddAction.CreateEvent && startsWithTaskVerb(lower) -> {
                    val sub = parseClause(extra, ctx, mutableSetOf())
                    val title = (sub as? QuickAddAction.CreateTask)?.title ?: capitalize(extra)
                    val due = (sub as? QuickAddAction.CreateTask)?.due ?: dueBefore(main.date, ctx.today)
                    actions += QuickAddAction.CreateTask(
                        title = title,
                        assigneeId = (sub as? QuickAddAction.CreateTask)?.assigneeId,
                        due = due,
                        linkedToEvent = true,
                        relatedPersonIds = main.participantIds,
                    )
                }
                main is QuickAddAction.CreateEvent && LOCATION_PREFIX.containsMatchIn(lower) && main.location.isEmpty() -> {
                    actions[0] = main.copy(location = extra.replace(LOCATION_PREFIX_TRIM, "").trim())
                }
                else -> {
                    val sub = parseClause(extra, ctx, mutableSetOf())
                    actions += sub
                }
            }
        }
        return QuickAddResult(input, actions, uncertain)
    }

    // region shopping

    /** Returns items when the whole input is a shopping list ("mjölk, 2 gurka, pasta", "köp mjölk och bröd"). */
    private fun shoppingList(text: String): List<ParsedItem>? {
        var body = text
        val prefix = SHOPPING_PREFIX.find(body.lowercase())
        val explicit = prefix != null
        if (prefix != null) body = body.substring(prefix.range.last + 1).trim()
        if (body.isEmpty()) return null
        val parts = splitItems(body)
        if (parts.isEmpty()) return null
        if (parts.any { !isItemLike(it) }) return null
        val looksLikeGroceries = parts.any { p -> isGrocery(p) || QuantityParser.parsePrefix(p).quantity != null }
        if (!explicit && parts.size < 2 && !looksLikeGroceries) return null
        if (explicit && !looksLikeGroceries && parts.size == 1) return null
        return parts.map { p ->
            val q = QuantityParser.parsePrefix(p)
            ParsedItem(name = q.rest.ifEmpty { p }.trim(), quantity = q.quantity)
        }
    }

    private fun splitItems(body: String): List<String> = body
        .split(Regex("""(?<!\d),|,(?!\d)|;|\n| och | samt | & | \+ """))
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotEmpty() }

    private fun isItemLike(part: String): Boolean {
        val lower = part.lowercase()
        if (lower.split(' ').size > 5) return false
        if (DATE_OR_TIME_HINT.containsMatchIn(lower)) return false
        if (startsWithTaskVerb(lower)) return false
        if (lower.split(' ').any { it in SwedishLexicon.activityTags || it in SwedishLexicon.pickupVerbs || it in SwedishLexicon.dropoffVerbs }) {
            return false
        }
        return true
    }

    private fun isGrocery(part: String): Boolean {
        val name = QuantityParser.parsePrefix(part).rest.lowercase().trim()
        return name in SwedishLexicon.groceries || name.split(' ').any { it in SwedishLexicon.groceries }
    }

    // endregion

    private fun splitClauses(text: String): List<String> =
        text.split(Regex("""(?<!\d),|,(?!\d)|;|\n""")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseClause(clause: String, ctx: QuickAddContext, uncertain: MutableSet<UncertainField>): QuickAddAction {
        val scan = Scan(clause)

        val mealMarker = scan.consume(MEAL_MARKER)
        val persons = extractPersons(scan, ctx)
        val handoff = extractHandoff(scan, childNamed = persons.any { it.person.isChild })
        val recurrence = extractRecurrence(scan)
        val dateInfo = extractDate(scan, ctx, recurrence)
        val times = extractTimes(scan, hasDateContext = dateInfo != null || recurrence != null)
        if (times?.uncertain == true) uncertain += UncertainField.Time

        val remainingLower = scan.remainingLower()
        val tags = remainingLower.split(' ').mapNotNull { SwedishLexicon.activityTags[it.trimEnd('s')] ?: SwedishLexicon.activityTags[it] }
            .distinct()
        val location = extractLocation(scan)
        val title = cleanTitle(scan.remaining())

        // Handoff: "Johan hämtar imorgon", "jag lämnar Simon på fredag".
        if (handoff != null) {
            val adult = persons.firstOrNull { !it.person.isChild && !it.isRecipient }?.person?.id ?: persons.firstOrNull { it.isSelf }?.person?.id
                ?: ctx.currentUserId
            if (adult != null) {
                val date = dateInfo?.date ?: recurrence?.days?.firstOrNull()?.let { nextWeekday(ctx, it, null) } ?: ctx.today.also {
                    uncertain += UncertainField.Date
                }
                if (persons.none { !it.person.isChild }) uncertain += UncertainField.Person
                return QuickAddAction.AssignHandoff(
                    personId = adult,
                    kind = handoff,
                    date = date,
                    childIds = persons.filter { it.person.isChild }.map { it.person.id },
                    time = times?.start,
                    recurringDays = recurrence?.days.orEmpty(),
                )
            }
        }

        // Task: "köp present", "ring tandläkaren senast fredag", "Johan: boka tvättstugan".
        if (startsWithTaskVerb(title.lowercase()) || TASK_HINT.containsMatchIn(title.lowercase())) {
            val assignee = persons.firstOrNull { !it.person.isChild && !it.isRecipient }?.person?.id
            val taskTitle = title.replace(REMINDER_PREFIX, "")
            return QuickAddAction.CreateTask(
                title = capitalize(taskTitle).ifEmpty { capitalize(clause) },
                assigneeId = assignee,
                due = dateInfo?.date,
                relatedPersonIds = persons.filter { it.person.id != assignee }.map { it.person.id },
            )
        }

        // Meal: "tacos fredag", "middag: lasagne på torsdag".
        if ((mealMarker != null || isDish(title)) && times == null && tags.isEmpty() && recurrence == null) {
            val slot = when (mealMarker?.trim()) {
                "lunch" -> MealSlot.Lunch
                "frukost" -> MealSlot.Breakfast
                else -> MealSlot.Dinner
            }
            val date = dateInfo?.date ?: ctx.today.also { uncertain += UncertainField.Date }
            return QuickAddAction.PlanMeal(date = date, title = capitalize(title).ifEmpty { capitalize(clause) }, slot = slot)
        }

        val hasEventSignal = dateInfo != null || times != null || recurrence != null || tags.isNotEmpty()
        if (!hasEventSignal) {
            // A lone grocery word goes to the list; anything else becomes a to-do ("fånga snabbt, sortera sen").
            if (isGrocery(clause) && persons.isEmpty()) {
                val q = QuantityParser.parsePrefix(clause)
                return QuickAddAction.AddShoppingItems(listOf(ParsedItem(q.rest.ifEmpty { clause }, q.quantity)))
            }
            uncertain += UncertainField.Kind
            return QuickAddAction.CreateTask(
                title = capitalize(title).ifEmpty { capitalize(clause) },
                assigneeId = persons.firstOrNull { !it.person.isChild }?.person?.id,
            )
        }

        return buildEvent(clause, title, ctx, persons, recurrence, dateInfo, times, tags, location, uncertain)
    }

    private fun buildEvent(
        clause: String,
        title: String,
        ctx: QuickAddContext,
        persons: List<PersonHit>,
        recurrence: RecurrenceInfo?,
        dateInfo: DateInfo?,
        times: TimeInfo?,
        tags: List<String>,
        location: String,
        uncertain: MutableSet<UncertainField>,
    ): QuickAddAction.CreateEvent {
        var date: LocalDate = when {
            dateInfo != null -> dateInfo.date
            recurrence?.days?.isNotEmpty() == true -> recurrence.days.minOf { nextWeekday(ctx, it, times?.start) }
            times?.start != null -> if (times.start > ctx.now) ctx.today else ctx.today.plusDays(1)
            else -> ctx.today
        }
        if (dateInfo == null && recurrence?.days.isNullOrEmpty()) uncertain += UncertainField.Date
        if (dateInfo?.uncertain == true) uncertain += UncertainField.Date

        val rrule = recurrence?.let { r ->
            when (r.freq) {
                Frequency.WEEKLY -> {
                    val days = r.days.ifEmpty { setOf(date.dayOfWeek) }
                    if (date.dayOfWeek !in days) date = days.minOf { nextWeekday(ctx, it, times?.start) }
                    RRule(Frequency.WEEKLY, r.interval, byDay = days.sortedBy { it.isoDayNumber }.map { WeekdayNum(it) })
                }
                Frequency.DAILY -> RRule(Frequency.DAILY, r.interval)
                Frequency.MONTHLY -> RRule(Frequency.MONTHLY, r.interval, byMonthDay = listOf(date.day))
                Frequency.YEARLY -> RRule(Frequency.YEARLY, r.interval)
            }
        }

        val allDayTag = tags.any { it in SwedishLexicon.allDayTags }
        val start = if (allDayTag && times == null) null else times?.start
        var end = times?.end
        if (start != null && end == null) {
            end = if (start.hour >= 23) LocalTime(23, 59) else LocalTime(start.hour + 1, start.minute)
        }
        var endDate = dateInfo?.endDate
        if (dateInfo?.weekOnly == true) {
            when {
                start != null -> {
                    endDate = null
                    uncertain += UncertainField.Date
                }
                // School breaks are Monday–Friday.
                EventTags.HOLIDAY_BREAK in tags -> endDate = date.plusDays(4)
                !allDayTag -> uncertain += UncertainField.Date
            }
        }
        if (start != null) endDate = null
        val participants = persons.filter { !it.isRecipient || it.person.isChild }.map { it.person.id }.distinct()
        val finalTitle = capitalize(title).ifEmpty {
            uncertain += UncertainField.Title
            capitalize(clause)
        }
        return QuickAddAction.CreateEvent(
            title = finalTitle,
            date = date,
            startTime = start,
            endTime = if (start == null) null else end,
            endDate = endDate,
            participantIds = participants,
            recurrence = rrule,
            location = location,
            tags = tags,
        )
    }

    // region persons

    private data class PersonHit(val person: FamilyPerson, val isSelf: Boolean, val isRecipient: Boolean)

    private fun extractPersons(scan: Scan, ctx: QuickAddContext): List<PersonHit> {
        val hits = mutableListOf<Pair<Int, PersonHit>>()
        val names = ctx.people.flatMap { p -> (listOf(p.name) + p.aliases).map { it.lowercase().trim() to p } }
            .filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.length }
        for ((rawName, person) in names) {
            val name = Regex.escape(rawName)
            while (true) {
                val m = scan.find(Regex("""(?:(till|åt|för|with|for)\s+)?$B$name(?:s)?$E""")) ?: break
                val recipient = m.groupValues[1].isNotEmpty()
                scan.take(m.range)
                hits += m.range.first to PersonHit(person, isSelf = false, isRecipient = recipient)
            }
        }
        val selfId = ctx.currentUserId
        val self = ctx.people.firstOrNull { it.id == selfId }
        if (self != null) {
            val m = scan.find(Regex("""$B(jag|mig)$E"""))
            if (m != null) {
                scan.take(m.range)
                hits += m.range.first to PersonHit(self, isSelf = true, isRecipient = false)
            }
        }
        return hits.sortedBy { it.first }.map { it.second }.distinctBy { it.person.id }
    }

    // endregion

    // region handoff

    private fun extractHandoff(scan: Scan, childNamed: Boolean): HandoffKind? {
        val pick = scan.find(PICKUP) ?: if (childNamed) scan.find(PICKUP_IMPERATIVE) else null
        val drop = scan.find(DROPOFF) ?: if (childNamed) scan.find(DROPOFF_IMPERATIVE) else null
        return when {
            pick != null -> {
                scan.take(pick.range)
                scan.consume(HANDOFF_FILLER)
                HandoffKind.PickUp
            }
            drop != null -> {
                scan.take(drop.range)
                scan.consume(HANDOFF_FILLER)
                HandoffKind.DropOff
            }
            else -> null
        }
    }

    // endregion

    // region recurrence

    private data class RecurrenceInfo(val freq: Frequency, val interval: Int = 1, val days: Set<DayOfWeek> = emptySet())

    private fun extractRecurrence(scan: Scan): RecurrenceInfo? {
        scan.find(WEEKDAYS_MON_FRI)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.WEEKLY, 1, WORKDAYS)
        }
        scan.find(EVERY_DAY)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.DAILY)
        }
        scan.find(EVERY_WEEKDAY_LIST)?.let { m ->
            scan.take(m.range)
            val interval = if (m.groupValues[1].startsWith("varannan") || m.groupValues[1].contains("other")) 2 else 1
            val days = WEEKDAY_TOKEN.findAll(m.value.lowercase()).mapNotNull { SwedishLexicon.weekdays[it.value] }.toSet()
            return RecurrenceInfo(Frequency.WEEKLY, interval, days)
        }
        scan.find(PLURAL_WEEKDAYS)?.let { m ->
            scan.take(m.range)
            val days = PLURAL_TOKEN.findAll(m.value).mapNotNull { SwedishLexicon.weekdayPlurals[it.value] }.toSet()
            return RecurrenceInfo(Frequency.WEEKLY, 1, days)
        }
        scan.find(EVERY_OTHER_WEEK)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.WEEKLY, 2)
        }
        scan.find(EVERY_NTH_WEEK)?.let { m ->
            scan.take(m.range)
            val n = m.groupValues[1].toIntOrNull() ?: ORDINALS[m.groupValues[1]] ?: 1
            return RecurrenceInfo(Frequency.WEEKLY, n)
        }
        scan.find(EVERY_WEEK)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.WEEKLY)
        }
        scan.find(EVERY_MONTH)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.MONTHLY)
        }
        scan.find(EVERY_YEAR)?.let {
            scan.take(it.range)
            return RecurrenceInfo(Frequency.YEARLY)
        }
        return null
    }

    // endregion

    // region dates

    private data class DateInfo(
        val date: LocalDate,
        val endDate: LocalDate? = null,
        val uncertain: Boolean = false,
        /** Only a week was given ("v 44", "nästa vecka"). */
        val weekOnly: Boolean = false,
    )

    private fun extractDate(scan: Scan, ctx: QuickAddContext, recurrence: RecurrenceInfo?): DateInfo? {
        val today = ctx.today
        scan.consume(DEADLINE_WORD)

        scan.find(ISO_DATE)?.let { m ->
            scan.take(m.range)
            val (y, mo, d) = m.destructured
            return runCatching { DateInfo(LocalDate(y.toInt(), mo.toInt(), d.toInt())) }.getOrNull()
        }
        scan.find(DAY_RANGE_MONTH)?.let { m ->
            scan.take(m.range)
            val month = SwedishLexicon.months.getValue(m.groupValues[3])
            val start = futureDate(today, month, m.groupValues[1].toInt()) ?: return null
            val end = runCatching { LocalDate(start.year, month, m.groupValues[2].toInt()) }.getOrNull()
            return DateInfo(start, end?.takeIf { it >= start })
        }
        scan.find(DAY_MONTH)?.let { m ->
            scan.take(m.range)
            val month = SwedishLexicon.months.getValue(m.groupValues[2])
            val year = m.groupValues[3].toIntOrNull()
            val day = m.groupValues[1].toInt()
            val date = if (year != null) runCatching { LocalDate(year, month, day) }.getOrNull() else futureDate(today, month, day)
            return date?.let { DateInfo(it) }
        }
        scan.find(NUMERIC_DATE)?.let { m ->
            scan.take(m.range)
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearRaw = m.groupValues[3].toIntOrNull()
            val year = yearRaw?.let { if (it < 100) 2000 + it else it }
            val date = if (year != null) runCatching { LocalDate(year, month, day) }.getOrNull() else futureDate(today, month, day)
            return date?.let { DateInfo(it) }
        }
        scan.find(DAY_AFTER_TOMORROW)?.let {
            scan.take(it.range)
            return DateInfo(today.plusDays(2))
        }
        scan.find(TOMORROW)?.let {
            scan.take(it.range)
            return DateInfo(today.plusDays(1))
        }
        scan.find(TODAY)?.let {
            scan.take(it.range)
            return DateInfo(today)
        }
        scan.find(WEEKEND)?.let {
            scan.take(it.range)
            val sat = if (today.dayOfWeek == DayOfWeek.SUNDAY) today else nextWeekday(ctx, DayOfWeek.SATURDAY, null)
            return DateInfo(sat)
        }
        scan.find(IN_N_UNITS)?.let { m ->
            scan.take(m.range)
            val n = m.groupValues[1].toIntOrNull() ?: SwedishLexicon.countWords[m.groupValues[1]] ?: 1
            val days = if (m.groupValues[2].startsWith("veck") || m.groupValues[2].startsWith("week")) n * 7 else n
            return DateInfo(today.plusDays(days))
        }

        // Week number or "nästa vecka", optionally combined with a weekday anywhere in the clause.
        // "nästa vecka" first, so "nästa vecka 17" is not read as week 17.
        var week: IsoWeek? = null
        scan.find(NEXT_WEEK)?.let {
            scan.take(it.range)
            week = today.isoWeek.next()
        }
        if (week == null) {
            scan.find(WEEK_NUMBER)?.let { m ->
                scan.take(m.range)
                val w = m.groupValues[1].toInt()
                val current = today.isoWeek
                // A week number that has already passed this year means next year.
                val year = if (w < current.week) current.weekYear + 1 else current.weekYear
                week = runCatching { IsoWeek(year, w) }.getOrNull() ?: runCatching { IsoWeek(year + 1, w) }.getOrNull()
            }
        }
        val weekdayMatch = scan.find(WEEKDAY_EXPR)
        if (weekdayMatch != null) {
            scan.take(weekdayMatch.range)
            val day = SwedishLexicon.weekdays[weekdayMatch.groupValues[2]] ?: return null
            val modifier = weekdayMatch.groupValues[1]
            val w = week
            return when {
                w != null -> DateInfo(w.monday.plusDays(day.isoDayNumber - 1))
                modifier.startsWith("nästa") || modifier == "next" -> {
                    DateInfo(today.isoWeek.next().monday.plusDays(day.isoDayNumber - 1), uncertain = true)
                }
                else -> {
                    val d = nextWeekday(ctx, day, null)
                    DateInfo(d, uncertain = d == today && recurrence == null)
                }
            }
        }
        week?.let { w ->
            return DateInfo(w.monday, endDate = w.sunday, weekOnly = true)
        }
        scan.find(DAY_OF_MONTH)?.let { m ->
            scan.take(m.range)
            val day = m.groupValues[1].toInt()
            val thisMonth = runCatching { LocalDate(today.year, today.month, day) }.getOrNull()
            val date = if (thisMonth != null && thisMonth >= today) {
                thisMonth
            } else {
                val next = LocalDate(today.year, today.month, 1).plusDays(RecurrenceExpander.monthLength(today.year, today.month.ordinal + 1))
                runCatching { LocalDate(next.year, next.month, day) }.getOrNull()
            }
            return date?.let { DateInfo(it, uncertain = true) }
        }
        return null
    }

    /** Next date (today or later) in [month]/[day]; rolls to next year if already passed. */
    private fun futureDate(today: LocalDate, month: Int, day: Int): LocalDate? {
        val thisYear = runCatching { LocalDate(today.year, month, day) }.getOrNull()
        return if (thisYear != null && thisYear >= today.minusDays(1)) thisYear else runCatching { LocalDate(today.year + 1, month, day) }.getOrNull()
    }

    private fun nextWeekday(ctx: QuickAddContext, day: DayOfWeek, time: LocalTime?): LocalDate {
        val diff = (day.isoDayNumber - ctx.today.dayOfWeek.isoDayNumber + 7) % 7
        if (diff == 0 && time != null && time <= ctx.now) return ctx.today.plusDays(7)
        return ctx.today.plusDays(diff)
    }

    private fun dueBefore(eventDate: LocalDate, today: LocalDate): LocalDate = maxOf(today, eventDate.minusDays(1))

    // endregion

    // region times

    private data class TimeInfo(val start: LocalTime, val end: LocalTime?, val uncertain: Boolean)

    private fun extractTimes(scan: Scan, hasDateContext: Boolean): TimeInfo? {
        scan.find(BETWEEN_TIMES)?.let { m ->
            scan.take(m.range)
            return range(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }
        scan.find(TIME_RANGE)?.let { m ->
            scan.take(m.range)
            return range(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }
        scan.find(HALF_QUARTER)?.let { m ->
            scan.take(m.range)
            val hourRaw = m.groupValues[2].toIntOrNull() ?: SwedishLexicon.hourWords[m.groupValues[2]] ?: return null
            val (h, min) = when (m.groupValues[1]) {
                "halv" -> (hourRaw - 1) to 30
                "kvart över" -> hourRaw to 15
                else -> (hourRaw - 1) to 45 // "kvart i"
            }
            val hour = ((h + 24) % 24).let { if (it in 1..6) it + 12 else it }
            return TimeInfo(LocalTime(hour, min), null, uncertain = true)
        }
        scan.find(SINGLE_TIME)?.let { m ->
            scan.take(m.range)
            val h = m.groupValues[1].toIntOrNull() ?: m.groupValues[3].toInt()
            val min = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: 0
            return single(h, min)
        }
        // "fotboll tis 17": a bare hour next to a date is almost always a time.
        if (hasDateContext) {
            scan.find(BARE_HOUR)?.let { m ->
                val h = m.groupValues[1].toInt()
                if (h in 6..23) {
                    scan.take(m.range)
                    return TimeInfo(LocalTime(h, 0), null, uncertain = true)
                }
            }
        }
        return null
    }

    private fun single(h: Int, min: Int): TimeInfo? {
        if (h !in 0..24 || min !in 0..59) return null
        val pm = h in 1..6
        val hour = if (pm) h + 12 else h % 24
        return TimeInfo(LocalTime(hour, min), null, uncertain = pm)
    }

    private fun range(h1: String, m1: String, h2: String, m2: String): TimeInfo? {
        var start = h1.toIntOrNull() ?: return null
        var end = h2.toIntOrNull() ?: return null
        val sm = m1.toIntOrNull() ?: 0
        val em = m2.toIntOrNull() ?: 0
        if (start !in 0..24 || end !in 0..24 || sm !in 0..59 || em !in 0..59) return null
        var uncertain = false
        // "kalas 2-4" means 14-16.
        if (start in 1..6) {
            start += 12
            uncertain = true
        }
        if (end < start && end + 12 <= 24) {
            end += 12
            uncertain = true
        }
        if (end == 24) end = 0
        val s = LocalTime(start % 24, sm)
        val e = if (end == 0 && em == 0) LocalTime(23, 59) else LocalTime(end, em)
        return TimeInfo(s, e, uncertain)
    }

    // endregion

    private fun extractLocation(scan: Scan): String {
        val m = scan.find(LOCATION_IN_CLAUSE) ?: return ""
        // Only treat "på/i X" as a place when X is capitalised in the original text ("på Vallen", "i Uppsala").
        val original = scan.originalAt(m.groups[2]?.range ?: return "")
        if (original.firstOrNull()?.isUpperCase() != true) return ""
        scan.take(m.range)
        return original
    }

    private fun isDish(title: String): Boolean {
        val t = title.lowercase().trim()
        if (t.isEmpty()) return false
        if (t in SwedishLexicon.dishes) return true
        val words = t.split(' ')
        return words.size <= 4 && SwedishLexicon.dishes.any { dish -> dish.contains(' ').not() && dish in words }
    }

    private fun startsWithTaskVerb(lower: String): Boolean {
        val t = lower.trim()
        return SwedishLexicon.taskVerbs.any { v -> t == v || t.startsWith("$v ") }
    }

    private fun cleanTitle(raw: String): String {
        var words = raw.replace(Regex("""\s+[-–:]\s+"""), " ").split(' ').filter { it.isNotBlank() }
        while (words.isNotEmpty() && words.first().lowercase().trim(',', '.', ':') in SwedishLexicon.danglingWords) words = words.drop(1)
        while (words.isNotEmpty() && words.last().lowercase().trim(',', '.', ':') in SwedishLexicon.danglingWords) words = words.dropLast(1)
        return words.joinToString(" ").trim(' ', ',', ':', '-', '–')
    }

    private fun capitalize(s: String): String = s.trim().replaceFirstChar { it.uppercase() }

    /** Text with consumed spans blanked out. Lowercase and original stay index-aligned. */
    private class Scan(original: String) {
        private val orig = original.toCharArray()
        private val low = original.lowercase().let { if (it.length == original.length) it.toCharArray() else original.toCharArray() }

        fun find(regex: Regex): MatchResult? = regex.find(String(low))

        fun take(range: IntRange) {
            for (i in range) {
                orig[i] = ' '
                low[i] = ' '
            }
        }

        fun consume(regex: Regex): String? {
            val m = find(regex) ?: return null
            take(m.range)
            return m.groupValues.getOrNull(1)?.ifEmpty { m.value } ?: m.value
        }

        fun originalAt(range: IntRange): String = String(orig, range.first, range.last - range.first + 1)

        fun remaining(): String = String(orig).replace(Regex("""\s+"""), " ").trim()

        fun remainingLower(): String = String(low).replace(Regex("""\s+"""), " ").trim()
    }

    private companion object {
        const val B = """(?<![\p{L}\d])"""
        const val E = """(?![\p{L}\d])"""

        fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

        val WD = alt(SwedishLexicon.weekdays.keys)
        val MONTHS = alt(SwedishLexicon.months.keys)
        val NUM_WORDS = alt(SwedishLexicon.countWords.keys)
        val HOUR_WORDS = alt(SwedishLexicon.hourWords.keys)
        val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val ORDINALS = mapOf("annan" to 2, "andra" to 2, "tredje" to 3, "fjärde" to 4)

        val WEEKDAY_TOKEN = Regex("""$B($WD)$E""")
        val PLURAL_TOKEN = Regex(alt(SwedishLexicon.weekdayPlurals.keys))

        val SHOPPING_PREFIX = Regex("""^(köp|köpa|handla|inköp|att handla|shopping|buy)\s*:?\s+""")
        val MEAL_MARKER = Regex("""^(middag|lunch|frukost|dinner)\s*[:\-]?\s+|$B(till middag|till lunch)$E""")
        val PACKING_PREFIX = Regex("""^(ta med|packa|glöm inte|med sig|bring)\s+""")
        val LOCATION_PREFIX = Regex("""^(hos|på|i|vid)\s+""")
        val LOCATION_PREFIX_TRIM = Regex("""^(på|i|vid)\s+""", RegexOption.IGNORE_CASE)
        val TASK_HINT = Regex("""$B(kom ihåg|glöm inte|måste|remember to)$E""")
        val REMINDER_PREFIX = Regex("""^(kom ihåg att|kom ihåg|glöm inte att|måste|remember to)\s+""", RegexOption.IGNORE_CASE)
        val DATE_OR_TIME_HINT = Regex(
            """$B(idag|i dag|imorgon|i morgon|övermorgon|ikväll|i kväll|helgen|kl|klockan|v\.?\s*\d|vecka|nästa|varje|varannan|$WD)$E|\d{1,2}[:.]\d{2}|\d{1,2}/\d{1,2}""",
        )

        val PICKUP = Regex("""$B(hämtar|hämtning(?:en)?|picks up)$E""")
        val DROPOFF = Regex("""$B(lämnar|lämning(?:en)?|drops off)$E""")

        /** Imperatives only mean pick-up/drop-off when a child is named ("hämta Ella"), not "hämta ut paketet". */
        val PICKUP_IMPERATIVE = Regex("""$B(hämta|pick up)$E(?!\s+(ut|in|upp)$E)""")
        val DROPOFF_IMPERATIVE = Regex("""$B(lämna|drop off)$E(?!\s+(in|tillbaka|ut)$E)""")
        val HANDOFF_FILLER = Regex("""$B(på (förskolan|skolan|fritids|dagis)|från (förskolan|skolan|fritids|dagis))$E""")

        val WEEKDAYS_MON_FRI = Regex("""$B(varje vardag|alla vardagar|vardagar|vardagarna|weekdays|(mån(dag)?|må)\s*[-–]\s*(fre(dag)?))$E""")
        val EVERY_DAY = Regex("""$B(varje dag|dagligen|every day|daily)$E""")
        val EVERY_WEEKDAY_LIST = Regex("""$B(varje|varannan|every other|every)\s+($WD)((\s*(,|och|&|and)\s*($WD))*)$E""")
        val PLURAL_WEEKDAYS = Regex(
            """$B(?:på\s+)?(${alt(SwedishLexicon.weekdayPlurals.keys)})((\s*(,|och|&|and)\s*(${alt(SwedishLexicon.weekdayPlurals.keys)}))*)$E""",
        )
        val EVERY_OTHER_WEEK = Regex("""$B(varannan vecka|var annan vecka|every other week|biweekly)$E""")
        val EVERY_NTH_WEEK = Regex("""$B(?:var|every)\s+(\d+|tredje|fjärde|andra)(?::?e|:a)?\s+(vecka|week)$E""")
        val EVERY_WEEK = Regex("""$B(varje vecka|en gång i veckan|every week|weekly|veckovis)$E""")
        val EVERY_MONTH = Regex("""$B(varje månad|en gång i månaden|every month|monthly|månadsvis)$E""")
        val EVERY_YEAR = Regex("""$B(varje år|årligen|every year|yearly|annually)$E""")

        val DEADLINE_WORD = Regex("""$B(senast|före|innan|by|before)$E(?=\s)""")
        val ISO_DATE = Regex("""$B(\d{4})-(\d{2})-(\d{2})$E""")
        val DAY_RANGE_MONTH = Regex("""$B(\d{1,2})\s*[-–]\s*(\d{1,2})\s+($MONTHS)\.?$E""")
        val DAY_MONTH = Regex("""$B(?:den\s+)?(\d{1,2})(?::e|:a|e|a)?\s+($MONTHS)\.?(?:\s+(\d{4}))?$E""")
        val NUMERIC_DATE = Regex("""$B(?:den\s+)?(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?$E""")
        val DAY_AFTER_TOMORROW = Regex("""$B(i\s+)?övermorgon$E""")
        val TOMORROW = Regex("""$B(i\s*morgon(\s+bitti)?|tomorrow)$E""")
        val TODAY = Regex("""$B(i\s*dag|i\s*kväll|i\s*eftermiddag|i\s*förmiddag|today|tonight)$E""")
        val WEEKEND = Regex("""$B(i\s+helgen|helgen|this weekend|weekend)$E""")
        val IN_N_UNITS = Regex("""$B(?:om|in)\s+(\d+|$NUM_WORDS)\s+(dagar|dag|veckor|vecka|days|day|weeks|week)$E""")
        val WEEK_NUMBER = Regex("""$B(?:v\.?|vecka|week|w)\s*(\d{1,2})$E""")
        val NEXT_WEEK = Regex("""$B(nästa vecka|next week)$E""")
        val WEEKDAY_EXPR = Regex("""$B(?:(nästa|kommande|på|next|on|den här|denna)\s+)?($WD)\.?$E""")
        val DAY_OF_MONTH = Regex("""$B(?:den)\s+(\d{1,2})(?::e|:a)?$E""")

        val BETWEEN_TIMES = Regex("""$B(?:mellan|från|from)\s+(?:kl\.?\s*)?(\d{1,2})(?:[:.](\d{2}))?\s+(?:och|till|to|-)\s+(?:kl\.?\s*)?(\d{1,2})(?:[:.](\d{2}))?$E""")
        val TIME_RANGE = Regex("""$B(?:kl\.?\s*|klockan\s*)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:-|–|till|to)\s*(\d{1,2})(?:[:.](\d{2}))?$E""")
        val HALF_QUARTER = Regex("""$B(?:kl\.?\s*)?(halv|kvart över|kvart i)\s+(\d{1,2}|$HOUR_WORDS)$E""")
        val SINGLE_TIME = Regex("""$B(?:kl\.?|klockan|at)\s*(\d{1,2})(?:[:.](\d{2}))?$E|$B(\d{1,2})[:.](\d{2})$E""")
        val BARE_HOUR = Regex("""$B(\d{1,2})$E""")
        val LOCATION_IN_CLAUSE = Regex("""$B(på|i|vid)\s+([\p{L}][\p{L}\-]+)$E""")
    }
}

package se.pusslet.core.today

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.custody.CustodyCalculator
import se.pusslet.core.model.Child
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Household
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The rules behind the new Idag (UX plan, Steg 2): Morgonkollen as data, "ett faktum en gång", the dinner times, the
 * reminders in Kommande and which blocks have anything to show. The family is the demo's: Thursday 8 October 2026.
 */
class TodayHeroTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val thursday = LocalDate(2026, 10, 8)
    private val friday = thursday.plusDays(1)
    private val saturday = thursday.plusDays(2)

    private val sara = Member("sara", "Sara", Role.Parent, MemberColor.Sage)
    private val johan = Member("johan", "Johan", Role.Parent, MemberColor.Ocean)
    private val bengt = Member("bengt", "Bengt", Role.Caregiver, MemberColor.Slate, nickname = "Morfar")
    private val ella = Child("ella", "Ella", MemberColor.Rose, birthDate = LocalDate(2017, 3, 14))
    private val simon = Child("simon", "Simon", MemberColor.Plum, birthDate = LocalDate(2021, 6, 2))
    private val people = listOf(sara, johan, bengt).map(FamilyPerson::of) + listOf(ella, simon).map(FamilyPerson::of)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private val preschool = Event(
        "e-forskola-to",
        "Förskola",
        at(thursday, 8),
        at(thursday, 16),
        rrule = "FREQ=WEEKLY;BYDAY=TH",
        participantIds = listOf("simon"),
        dropOff = Handoff("johan", LocalTime(7, 50)),
        pickUp = Handoff("bengt", LocalTime(15, 30)),
        tags = listOf("förskola"),
    )
    private val school = Event(
        "e-skola-to",
        "Skola & fritids",
        at(thursday, 8, 15),
        at(thursday, 16),
        rrule = "FREQ=WEEKLY;BYDAY=TH",
        participantIds = listOf("ella"),
        dropOff = Handoff("johan", LocalTime(8, 5)),
        pickUp = Handoff("bengt", LocalTime(15, 45)),
        tags = listOf("skola"),
    )
    private val swimming = Event("e-sim", "Simskola", at(thursday, 17, 30), at(thursday, 18, 15), participantIds = listOf("ella"))
    private val trip = Event(
        "e-utflykt",
        "Skogsutflykt",
        at(friday, 0),
        at(saturday, 0),
        allDay = true,
        participantIds = listOf("simon"),
        tags = listOf(EventTags.TRIP),
    )
    private val studyDay = Event(
        "e-studiedag",
        "Studiedag",
        at(friday, 0),
        at(saturday, 0),
        allDay = true,
        participantIds = listOf("ella"),
        tags = listOf(EventTags.STUDY_DAY),
    )
    private val party = Event(
        "e-kalas",
        "Kalas hos Vera",
        at(saturday, 14),
        at(saturday, 16),
        participantIds = listOf("ella"),
        tags = listOf(EventTags.PARTY),
    )
    private val present = FamilyTask(
        "t-present",
        "Köp present till Vera",
        category = TaskCategory.Gift,
        assigneeId = "sara",
        linkedEventId = "e-kalas",
    )
    private val gympa = PackingRule("p-gympa", "Gympapåse", "ella", PackingTrigger.Weekly(setOf(DayOfWeek.THURSDAY)))

    private val thisWeek = MealPlanWeek(thursday.isoWeek)
        .with(MealEntry(thursday, title = "Fiskgratäng"))
        .with(MealEntry(friday, title = "Tacos"))
        .with(MealEntry(saturday, title = "Pizza"))

    private fun input(
        now: LocalTime = LocalTime(7, 10),
        me: String? = "sara",
        events: List<Event> = listOf(preschool, school, swimming, trip, studyDay, party),
        mealWeeks: List<MealPlanWeek> = listOf(thisWeek),
        lunches: Map<String, List<SchoolLunch>> = emptyMap(),
        tasks: List<FamilyTask> = listOf(present),
        packingRules: List<PackingRule> = listOf(gympa),
        packingChecks: Map<String, PackingChecks> = emptyMap(),
        custody: List<CustodySchedule> = emptyList(),
        today: LocalDate = thursday,
    ) = TodayInput(
        today = today,
        now = now,
        zone = zone,
        me = me,
        people = people,
        children = listOf(ella, simon),
        occurrences = EventExpander.expand(events, today, today.plusDays(10), zone),
        packingRules = packingRules,
        packingChecks = packingChecks,
        custody = custody,
        mealWeeks = mealWeeks,
        lunches = lunches,
        tasks = tasks,
    )

    private fun plan(input: TodayInput = input()) = TodayPlanner.build(input)

    /** "Johan lämnar Simon 07:50 och Ella 08:05" as the UI would say it, to check the grouping at a glance. */
    private fun HandoffLine.sentence(): String {
        val who = if (isMe) "Du" else person?.name ?: "?"
        val verb = if (kind == HandoffKind.DropOff) "lämnar" else "hämtar"
        return "$who $verb " + stops.joinToString(" och ") { "${it.child.name} ${it.time}" }
    }

    private val parent = TodayLayouts.defaultFor(Role.Parent)

    @Test
    fun morgonkollenGroupsHandoffsByPersonAndKind() {
        val lines = plan().hero.handoffs
        assertEquals(
            listOf("Johan lämnar Simon 07:50 och Ella 08:05", "Bengt hämtar Simon 15:30 och Ella 15:45"),
            lines.map { it.sentence() },
        )
        assertEquals(LocalTime(7, 50), lines.first().firstTime)
        assertEquals(listOf("e-forskola-to", "e-skola-to"), lines.first().stops.map { it.occurrence.event.id })
        assertFalse(lines.any { it.isMe })
    }

    @Test
    fun myOwnHandoffsSayDu() {
        val lines = plan(input(me = "johan")).hero.handoffs
        assertEquals("Du lämnar Simon 07:50 och Ella 08:05", lines.first().sentence())
        assertTrue(lines.first().isMe)
        assertFalse(lines.last().isMe)
    }

    @Test
    fun passedHandoffsLeaveMorgonkollen() {
        assertEquals(
            listOf("Johan lämnar Ella 08:05", "Bengt hämtar Simon 15:30 och Ella 15:45"),
            plan(input(now = LocalTime(7, 55))).hero.handoffs.map { it.sentence() },
        )
        // At the minute itself it is still ahead.
        assertEquals("Johan lämnar Ella 08:05", plan(input(now = LocalTime(8, 5))).hero.handoffs.first().sentence())
        val afterDropOff = plan(input(now = LocalTime(8, 6))).hero.handoffs
        assertEquals(listOf("Bengt hämtar Simon 15:30 och Ella 15:45"), afterDropOff.map { it.sentence() })
        val evening = plan(input(now = LocalTime(16, 0)))
        assertTrue(evening.hero.handoffs.isEmpty())
        assertFalse(TodayBlock.Handoffs in TodayLayouts.visibleBlocks(parent, evening))
        // A plan from the morning judged later: the row has passed.
        assertFalse(TodayBlock.Handoffs in TodayLayouts.visibleBlocks(parent, plan(), now = LocalTime(16, 0)))
    }

    @Test
    fun aHandoffWithAProblemIsOnlyUnderAttLosa() {
        // Nobody picks Ella up, and Bengt is at the dentist when Simon should be picked up.
        val dentist = Event("e-tand", "Tandläkare", at(thursday, 15), at(thursday, 15, 40), participantIds = listOf("bengt"))
        val events = listOf(preschool, school.copy(pickUp = Handoff(null, LocalTime(15, 45))), swimming, dentist)
        val plan = plan(input(events = events))
        assertEquals(listOf("Johan lämnar Simon 07:50 och Ella 08:05"), plan.hero.handoffs.map { it.sentence() })
        val problems = plan.problems.filter { it.date == thursday }
        assertEquals(LocalTime(15, 30), assertIs<Conflict.AssigneeBusy>(problems[0]).time)
        assertEquals(HandoffKind.PickUp, assertIs<Conflict.Unassigned>(problems[1]).kind)

        // Not in Idag händer either, even with the Morgonkollen row off.
        val handoffsOff = TodayLayouts.setVisible(parent, TodayBlock.Handoffs, false)
        val rows = plan.timelineFor(handoffsOff).filterIsInstance<TimelineItem.HandoffItem>().map { "${it.kind}@${it.time}" }
        assertEquals(listOf("DropOff@07:50", "DropOff@08:05"), rows)
    }

    @Test
    fun oneFactOnceHandoffsAreInTheTimelineOnlyWhenMorgonkollenDoesNotShowThem() {
        val plan = plan()
        val withHero = plan.timelineFor(parent)
        assertTrue(withHero.none { it is TimelineItem.HandoffItem })
        assertEquals(listOf("Simskola"), withHero.filterIsInstance<TimelineItem.EventItem>().map { it.occurrence.event.title })
        val handoffsOff = TodayLayouts.setVisible(parent, TodayBlock.Handoffs, false)
        assertEquals(4, plan.timelineFor(handoffsOff).count { it is TimelineItem.HandoffItem })
        // The old field is unchanged for the current screen.
        assertEquals(5, plan.timeline.size)
    }

    @Test
    fun packingRowAndAllPackedUntilTheLastDropOff() {
        val toPack = plan().hero.packing
        assertEquals(PackingState.ToPack, toPack.state)
        assertEquals(listOf("Ella"), toPack.children.map { it.child.name })
        assertEquals(LocalTime(8, 5), toPack.leavesAt)
        assertTrue(toPack.isShownAt(LocalTime(20, 0)))

        val checks = mapOf(PackingChecks.docId(thursday, "ella") to PackingChecks(thursday, "ella", mapOf("rule:p-gympa" to true)))
        val packed = plan(input(packingChecks = checks)).hero.packing
        assertTrue(packed.allPacked)
        assertEquals(1, packed.packed)
        assertTrue(packed.isShownAt(LocalTime(8, 5)))
        assertFalse(packed.isShownAt(LocalTime(8, 6)))
        assertFalse(TodayBlock.Packing in TodayLayouts.visibleBlocks(parent, plan(input(now = LocalTime(9, 0), packingChecks = checks))))

        assertEquals(PackingState.Nothing, plan(input(packingRules = emptyList())).hero.packing.state)
        assertFalse(plan(input(packingRules = emptyList())).hero.packing.isShownAt(LocalTime(7, 0)))
    }

    @Test
    fun allPackedWithoutADropOffLastsUntilTheLastEventOrAllDay() {
        val checks = mapOf(PackingChecks.docId(thursday, "ella") to PackingChecks(thursday, "ella", mapOf("rule:p-gympa" to true)))
        assertEquals(LocalTime(17, 30), plan(input(events = listOf(swimming), packingChecks = checks)).hero.packing.leavesAt)
        val quietDay = plan(input(events = emptyList(), packingChecks = checks)).hero.packing
        assertEquals(null, quietDay.leavesAt)
        assertTrue(quietDay.isShownAt(LocalTime(21, 0)))
    }

    @Test
    fun dinnerIsShownUntilEight() {
        val plan = plan()
        assertEquals("Fiskgratäng", plan.hero.dinner.dinner?.title)
        assertFalse(plan.hero.dinner.planPromptAt(LocalTime(7, 10)))
        assertTrue(plan.hero.dinner.isShownAt(LocalTime(19, 59)))
        assertFalse(plan.hero.dinner.isShownAt(LocalTime(20, 0)))
        assertFalse(TodayBlock.Dinner in TodayLayouts.visibleBlocks(parent, plan(input(now = LocalTime(20, 0)))))
        assertTrue(TodayBlock.Dinner in TodayLayouts.visibleBlocks(parent, plan(input(now = LocalTime(19, 0)))))
    }

    @Test
    fun planDinnerOnlyBeforeSixAndOnlyForAFamilyThatPlansDinners() {
        val noDinnerToday = thisWeek.without(thursday, MealSlot.Dinner)
        val prompt = plan(input(mealWeeks = listOf(noDinnerToday))).hero.dinner
        assertTrue(prompt.dinnerPlannedRecently)
        assertTrue(prompt.planPromptAt(LocalTime(17, 59)))
        assertFalse(prompt.planPromptAt(LocalTime(18, 0)))
        assertFalse(prompt.isShownAt(LocalTime(18, 0)))

        // Last week counts too (the view model must pass it).
        val lastWeek = MealPlanWeek(thursday.isoWeek.previous()).with(MealEntry(thursday.plusDays(-7), title = "Soppa"))
        assertTrue(plan(input(mealWeeks = listOf(lastWeek))).hero.dinner.planPromptAt(LocalTime(12, 0)))

        // Never planned (or only long ago, or only next week, or only a lunch): no nagging.
        val longAgo = MealPlanWeek(thursday.isoWeek.plusWeeks(-2)).with(MealEntry(thursday.plusDays(-14), title = "Soppa"))
        val nextWeek = MealPlanWeek(thursday.isoWeek.next()).with(MealEntry(thursday.plusDays(7), title = "Soppa"))
        val lunchOnly = MealPlanWeek(thursday.isoWeek).with(MealEntry(friday, MealSlot.Lunch, title = "Matlåda"))
        listOf(emptyList(), listOf(longAgo), listOf(nextWeek), listOf(lunchOnly)).forEach { weeks ->
            val dinner = plan(input(mealWeeks = weeks)).hero.dinner
            assertFalse(dinner.planPromptAt(LocalTime(12, 0)), weeks.toString())
            assertFalse(dinner.isShownAt(LocalTime(12, 0)))
        }
    }

    @Test
    fun childrenWithTheSameLunchShareOneRowAndTheClash() {
        val lunches = mapOf(
            "ella" to listOf(SchoolLunch(thursday, listOf("Köttbullar med potatis", "Vegetariska bullar"), "skolmaten")),
            "simon" to listOf(SchoolLunch(thursday, listOf(" köttbullar med potatis "), "skolmaten")),
        )
        val same = plan(input(lunches = lunches)).hero.dinner
        assertEquals(listOf(listOf("Ella", "Simon")), same.lunches.map { g -> g.children.map { it.name } })
        assertEquals("Köttbullar med potatis", same.lunches.single().dish)
        assertTrue(same.clashes.isEmpty())

        val meatballs = MealPlanWeek(thursday.isoWeek).with(MealEntry(thursday, title = "Köttbullar och mos"))
        val clash = plan(input(lunches = lunches, mealWeeks = listOf(meatballs))).hero.dinner
        assertEquals(setOf("köttbullar"), clash.clashes.single().clash)
        assertEquals(listOf("Ella", "Simon"), clash.clashes.single().children.map { it.name })

        val different = lunches + ("simon" to listOf(SchoolLunch(thursday, listOf("Fiskpinnar"), "skolmaten")))
        assertEquals(listOf("Köttbullar med potatis", "Fiskpinnar"), plan(input(lunches = different)).hero.dinner.lunches.map { it.dish })
    }

    @Test
    fun lunchMergeSkipsEmptyMenus() {
        val e = FamilyPerson.of(ella)
        val s = FamilyPerson.of(simon)
        val groups = LunchMerge.merge(listOf(ChildLunch(e, listOf("", " ")), ChildLunch(s, listOf(" ", "Pannkakor"))))
        assertEquals(listOf(LunchGroup(listOf(s), "Pannkakor")), groups)
    }

    @Test
    fun comingUpHasRemindersOnTheirDayAndNoDinners() {
        val days = plan().comingUp
        // Sunday has nothing, so it is left out; Saturday's dinner is not there.
        assertEquals(listOf(friday, saturday), days.map { it.date })

        val fri = days[0]
        assertEquals(listOf("Skogsutflykt"), fri.events.map { it.event.title })
        val study = assertIs<DayReminder.StudyDay>(fri.reminders.single())
        assertEquals(listOf("Ella"), study.children.map { it.name })

        val sat = days[1]
        assertTrue(sat.events.isEmpty(), "the party is carried by its reminder")
        val gift = assertIs<DayReminder.PartyGift>(sat.reminders.single())
        assertEquals("e-kalas", gift.occurrence.event.id)
        assertEquals("t-present", gift.taskId)
    }

    @Test
    fun aBoughtPresentIsJustAnEventAndAMissingTaskCanBeAdded() {
        val bought = plan(input(tasks = listOf(present.copy(done = true)))).comingUp.single { it.date == saturday }
        assertTrue(bought.reminders.isEmpty())
        assertEquals(listOf("Kalas hos Vera"), bought.events.map { it.event.title })
        val noTask = plan(input(tasks = emptyList())).comingUp.single { it.date == saturday }
        assertEquals(null, assertIs<DayReminder.PartyGift>(noTask.reminders.single()).taskId)
    }

    @Test
    fun comingUpSkipsRoutinesAndShowsAnEventOnce() {
        val weekdays = preschool.copy(id = "e-forskola", rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        // A three-day camp that starts tomorrow is shown on its first day only.
        val camp = Event("e-lager", "Lager", at(friday, 0), at(thursday.plusDays(4), 0), allDay = true, participantIds = listOf("ella"))
        val days = plan(input(events = listOf(weekdays, camp), tasks = emptyList())).comingUp
        assertEquals(listOf(friday), days.map { it.date })
        assertEquals(listOf("Lager"), days.single().events.map { it.event.title })
        // Something that already runs today belongs to today, not to Kommande.
        val running = Event("e-lov", "Höstlov", at(thursday, 0), at(saturday, 0), allDay = true, participantIds = listOf("ella"))
        assertTrue(plan(input(events = listOf(running), tasks = emptyList())).comingUp.isEmpty())
    }

    @Test
    fun squeezeDayIsAReminderOnItsDay() {
        val tuesday = LocalDate(2026, 5, 12)
        val squeeze = LocalDate(2026, 5, 15)
        val days = plan(input(today = tuesday, events = emptyList(), tasks = emptyList(), mealWeeks = emptyList())).comingUp
        // Thursday is Ascension Day (a red day), Friday the squeeze day.
        assertEquals(listOf(LocalDate(2026, 5, 14), squeeze), days.map { it.date })
        assertTrue(days[0].holidays.isNotEmpty())
        assertEquals(listOf<DayReminder>(DayReminder.SqueezeDay), days[1].reminders)
    }

    @Test
    fun custodyIsInTheHeaderNotACard() {
        val hemma = Household("h1", "Hemma", listOf("sara"))
        val ahmed = Household("h2", "Ahmed")
        // Alternating weeks, changeover on Thursdays at 15:00: today the children move to Ahmed.
        val schedule = CustodySchedule("c1", listOf("simon"), listOf(hemma, ahmed), anchorDate = thursday.plusDays(-7))
        assertTrue(CustodyCalculator.isHandoverDay(schedule, thursday))

        val before = assertIs<CustodyNote.Changeover>(plan(input(custody = listOf(schedule))).custodyNotes.single())
        assertEquals("Ahmed", before.handover.to.name)
        assertEquals(LocalTime(15, 0), before.handover.time)
        val after = assertIs<CustodyNote.With>(plan(input(now = LocalTime(15, 1), custody = listOf(schedule))).custodyNotes.single())
        assertEquals("Ahmed", after.household.name)
        val tomorrow = plan(input(today = friday, custody = listOf(schedule))).custodyNotes.single()
        assertEquals("Ahmed", assertIs<CustodyNote.With>(tomorrow).household.name)
    }

    @Test
    fun visibleBlocksLeaveOutEmptyBlocks() {
        // No weather place in this family, nothing for Sara to do and the shopping section is off.
        val plan = plan(input(tasks = listOf(present.copy(assigneeId = "johan"))))
        assertEquals(
            listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner, TodayBlock.Timeline, TodayBlock.Upcoming),
            TodayLayouts.visibleBlocks(parent, plan),
        )
        assertFalse(TodayLayouts.isQuiet(parent, plan))
        // Sara has the present to buy; with a weather place the row is there even before the forecast has loaded.
        val withTask = plan(input().copy(hasWeatherPlace = true))
        assertEquals(
            listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner, TodayBlock.Weather) +
                listOf(TodayBlock.Timeline, TodayBlock.Tasks, TodayBlock.Upcoming),
            TodayLayouts.visibleBlocks(parent, withTask),
        )
    }

    @Test
    fun shoppingShowsWhatIsLeftOrWhoIsShopping() {
        val on = TodayLayouts.setVisible(parent, TodayBlock.Shopping, true)

        fun visible(shopping: TodayShopping?): Boolean {
            val plan = plan(input().copy(shopping = shopping))
            return TodayBlock.Shopping in TodayLayouts.visibleBlocks(on, plan)
        }
        assertTrue(visible(TodayShopping("l-vecka", "Veckohandling", remaining = 5)))
        assertTrue(visible(TodayShopping("l-vecka", "Veckohandling", remaining = 0, shoppers = listOf(FamilyPerson.of(sara)))))
        assertFalse(visible(TodayShopping("l-vecka", "Veckohandling", remaining = 0)))
        assertFalse(visible(null))
        assertFalse(TodayBlock.Shopping in TodayLayouts.visibleBlocks(parent, plan(input().copy(shopping = TodayShopping("l", "L", 5)))))
    }

    @Test
    fun quietWhenNoSectionHasAnything() {
        // Late in the evening on a day with only routines: no timeline rows, nothing coming up, no tasks.
        val plan = plan(input(now = LocalTime(21, 0), events = listOf(preschool, school), tasks = emptyList()))
        assertTrue(TodayLayouts.isQuiet(parent, plan))
        // Every section switched off is quiet too, while Morgonkollen still has content.
        val sectionsOff = parent.sections.fold(parent) { l, item -> TodayLayouts.setVisible(l, item.block, false) }
        val morning = plan()
        assertTrue(TodayLayouts.isQuiet(sectionsOff, morning))
        assertEquals(listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Dinner), TodayLayouts.visibleBlocks(sectionsOff, morning))
        // Everything off: nothing to draw, but the problems are still in the plan (they can never be hidden).
        val allOff = parent.items.fold(parent) { l, item -> TodayLayouts.setVisible(l, item.block, false) }
        assertTrue(TodayLayouts.visibleBlocks(allOff, morning).isEmpty())
        val unassigned = plan(input(events = listOf(school.copy(pickUp = Handoff(null)))))
        assertTrue(TodayLayouts.visibleBlocks(allOff, unassigned).isEmpty())
        assertTrue(unassigned.problems.isNotEmpty())
    }

    @Test
    fun aCaregiverSeesHisOwnHandoffsAsDu() {
        val plan = plan(input(me = "bengt"))
        val caregiver = TodayLayouts.defaultFor(Role.Caregiver)
        assertEquals("Du hämtar Simon 15:30 och Ella 15:45", plan.hero.handoffs.last().sentence())
        assertEquals(listOf(TodayBlock.Handoffs, TodayBlock.Packing, TodayBlock.Upcoming), TodayLayouts.visibleBlocks(caregiver, plan))
    }

    @Test
    fun greetingIsStillComputedForTheMorningNotification() {
        assertEquals(Greeting.Morning, plan().greeting)
        assertEquals(LocalTime(7, 10), plan().now)
    }
}

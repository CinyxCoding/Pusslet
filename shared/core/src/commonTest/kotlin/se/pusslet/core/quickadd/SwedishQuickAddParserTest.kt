package se.pusslet.core.quickadd

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.recurrence.Frequency
import se.pusslet.core.units.MeasureUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quick-add parser cases. "Today" is Thursday 2026-10-08 (week 41) at 10:00.
 * Familjen Lind: Sara (current user), Johan, Ella (9), Simon (4) and morfar Bengt.
 */
class SwedishQuickAddParserTest {
    private val parser = SwedishQuickAddParser()
    private val today = LocalDate(2026, 10, 8)
    private val people = listOf(
        FamilyPerson("sara", "Sara", MemberColor.Sage, isChild = false, role = Role.Parent),
        FamilyPerson("johan", "Johan", MemberColor.Ocean, isChild = false, role = Role.Parent),
        FamilyPerson("ella", "Ella", MemberColor.Rose, isChild = true),
        FamilyPerson("simon", "Simon", MemberColor.Ochre, isChild = true),
        FamilyPerson("bengt", "Bengt", MemberColor.Slate, isChild = false, role = Role.Caregiver, aliases = listOf("Morfar")),
    )
    private val ctx = QuickAddContext(today, LocalTime(10, 0), people, currentUserId = "sara")

    private fun parse(s: String, c: QuickAddContext = ctx) = parser.parse(s, c)

    private fun event(s: String) = assertIs<QuickAddAction.CreateEvent>(parse(s).primary, s)

    private fun task(s: String) = assertIs<QuickAddAction.CreateTask>(parse(s).primary, s)

    private fun meal(s: String) = assertIs<QuickAddAction.PlanMeal>(parse(s).primary, s)

    private fun items(s: String) = assertIs<QuickAddAction.AddShoppingItems>(parse(s).primary, s).items

    private fun handoff(s: String) = assertIs<QuickAddAction.AssignHandoff>(parse(s).primary, s)

    private fun d(s: String) = LocalDate.parse(s)

    private fun t(h: Int, m: Int = 0) = LocalTime(h, m)

    // region The five examples from the brief

    @Test
    fun brief1_weeklyFootballForSimon() {
        val e = event("Simon fotboll tis 17:30 varje vecka")
        assertEquals("Fotboll", e.title)
        assertEquals(listOf("simon"), e.participantIds)
        assertEquals(d("2026-10-13"), e.date)
        assertEquals(t(17, 30), e.startTime)
        assertEquals(t(18, 30), e.endTime)
        val rule = assertNotNull(e.recurrence)
        assertEquals(Frequency.WEEKLY, rule.freq)
        assertEquals(listOf(DayOfWeek.TUESDAY), rule.byDay.map { it.day })
        assertTrue(EventTags.TRAINING in e.tags)
    }

    @Test
    fun brief2_partyWithPresentTask() {
        val r = parse("Ella kalas lör 14-16 hos Vera, köp present")
        assertEquals(2, r.actions.size)
        val e = assertIs<QuickAddAction.CreateEvent>(r.actions[0])
        assertEquals("Kalas hos Vera", e.title)
        assertEquals(d("2026-10-10"), e.date)
        assertEquals(t(14), e.startTime)
        assertEquals(t(16), e.endTime)
        assertEquals(listOf("ella"), e.participantIds)
        assertTrue(EventTags.PARTY in e.tags)
        val task = assertIs<QuickAddAction.CreateTask>(r.actions[1])
        assertEquals("Köp present", task.title)
        assertTrue(task.linkedToEvent)
        assertEquals(d("2026-10-09"), task.due)
    }

    @Test
    fun brief3_shoppingList() {
        val list = items("mjölk, 2 gurka, pasta")
        assertEquals(listOf("mjölk", "gurka", "pasta"), list.map { it.name })
        assertNull(list[0].quantity)
        assertEquals(2.0, list[1].quantity?.value)
        assertNull(list[2].quantity)
    }

    @Test
    fun brief4_johanPicksUpTomorrow() {
        val h = handoff("Johan hämtar imorgon")
        assertEquals("johan", h.personId)
        assertEquals(HandoffKind.PickUp, h.kind)
        assertEquals(d("2026-10-09"), h.date)
        assertTrue(h.childIds.isEmpty())
    }

    @Test
    fun brief5_tacosFriday() {
        val m = meal("tacos fredag")
        assertEquals("Tacos", m.title)
        assertEquals(d("2026-10-09"), m.date)
        assertEquals(MealSlot.Dinner, m.slot)
    }

    // endregion

    // region Dates

    @Test
    fun tomorrowVariants() {
        assertEquals(d("2026-10-09"), event("tandläkare imorgon 9:00").date)
        assertEquals(d("2026-10-09"), event("tandläkare i morgon kl 9").date)
    }

    @Test
    fun todayAndTonight() {
        assertEquals(today, event("simskola idag 18").date)
        val tonight = event("föräldramöte ikväll 19")
        assertEquals(today, tonight.date)
        assertEquals(t(19), tonight.startTime)
    }

    @Test
    fun dayAfterTomorrow() {
        assertEquals(d("2026-10-10"), event("utflykt i övermorgon").date)
    }

    @Test
    fun weekdayFullAndShortNames() {
        assertEquals(d("2026-10-12"), event("frisör måndag 15:00").date)
        assertEquals(d("2026-10-12"), event("frisör mån 15:00").date)
        assertEquals(d("2026-10-14"), event("möte onsdag 13").date)
        assertEquals(d("2026-10-14"), event("möte ons 13").date)
        assertEquals(d("2026-10-11"), event("bio sön 15").date)
    }

    @Test
    fun weekdayWithPaPrefix() {
        assertEquals(d("2026-10-13"), event("utvecklingssamtal på tisdag 8:30").date)
    }

    @Test
    fun todaysWeekdayMeansTodayButIsMarkedUncertain() {
        val r = parse("tandläkare torsdag 15")
        assertEquals(today, assertIs<QuickAddAction.CreateEvent>(r.primary).date)
        assertTrue(UncertainField.Date in r.uncertain)
    }

    @Test
    fun nextWeekday() {
        assertEquals(d("2026-10-16"), event("bowling nästa fredag 18").date)
        assertEquals(d("2026-10-13"), event("bowling nästa tisdag 18").date)
    }

    @Test
    fun weekNumberWithWeekday() {
        assertEquals(d("2026-10-27"), event("tandläkare tis v 44 kl 10").date)
        assertEquals(d("2026-10-29"), event("vaccination torsdag vecka 44 9:15").date)
    }

    @Test
    fun weekNumberAloneForBreakSpansMondayToFriday() {
        val e = event("höstlov v 44")
        assertTrue(e.allDay)
        assertEquals(d("2026-10-26"), e.date)
        assertEquals(d("2026-10-30"), e.endDate)
        assertTrue(EventTags.HOLIDAY_BREAK in e.tags)
    }

    @Test
    fun weekNumberEarlyNextYear() {
        assertEquals(d("2027-02-15"), event("sportlov v 7").date)
    }

    @Test
    fun nextWeekWithWeekday() {
        assertEquals(d("2026-10-14"), event("simning onsdag nästa vecka 17").date)
    }

    @Test
    fun numericDate() {
        assertEquals(d("2026-10-12"), event("vaccination 12/10 kl 14").date)
        assertEquals(d("2027-03-01"), event("vaccination 1/3 kl 14").date)
    }

    @Test
    fun dayAndMonthName() {
        assertEquals(d("2026-12-24"), event("julafton hos mormor 24 dec").date)
        assertEquals(d("2026-11-15"), event("kalas 15 november 14-17").date)
        assertEquals(d("2027-01-03"), event("skidresa 3 jan").date)
    }

    @Test
    fun isoDate() {
        assertEquals(d("2026-11-02"), event("studiedag 2026-11-02").date)
    }

    @Test
    fun dateRangeWithMonth() {
        val e = event("jullov 21-31 dec")
        assertEquals(d("2026-12-21"), e.date)
        assertEquals(d("2026-12-31"), e.endDate)
        assertTrue(e.allDay)
    }

    @Test
    fun inNDays() {
        assertEquals(d("2026-10-11"), event("kalas om 3 dagar 14").date)
        assertEquals(d("2026-10-15"), event("tandläkare om en vecka 9").date)
    }

    @Test
    fun dayOfMonth() {
        assertEquals(d("2026-10-20"), event("frisör den 20 kl 11").date)
    }

    @Test
    fun weekend() {
        assertEquals(d("2026-10-10"), event("utflykt i helgen").date)
    }

    @Test
    fun noDateButTimeLaterTodayIsToday() {
        val r = parse("simskola 17:00")
        assertEquals(today, assertIs<QuickAddAction.CreateEvent>(r.primary).date)
        assertTrue(UncertainField.Date in r.uncertain)
    }

    @Test
    fun noDateAndTimeAlreadyPassedIsTomorrow() {
        assertEquals(d("2026-10-09"), event("simskola 8:00").date)
    }

    // endregion

    // region Times

    @Test
    fun colonAndDotTimes() {
        assertEquals(t(17, 30), event("fotboll tis 17.30").startTime)
        assertEquals(t(8, 15), event("läkare fre 8:15").startTime)
    }

    @Test
    fun klPrefix() {
        assertEquals(t(9), event("tandläkare fre kl 9").startTime)
        assertEquals(t(9, 30), event("tandläkare fre kl. 9.30").startTime)
        assertEquals(t(15), event("tandläkare fre klockan 15").startTime)
    }

    @Test
    fun timeRangeWithMinutes() {
        val e = event("dans ons 17:15-18:00")
        assertEquals(t(17, 15), e.startTime)
        assertEquals(t(18), e.endTime)
    }

    @Test
    fun betweenTimes() {
        val e = event("föräldramöte tis mellan 18 och 19.30")
        assertEquals(t(18), e.startTime)
        assertEquals(t(19, 30), e.endTime)
    }

    @Test
    fun afternoonHeuristicForSmallHours() {
        val r = parse("kalas lör 2-4")
        val e = assertIs<QuickAddAction.CreateEvent>(r.primary)
        assertEquals(t(14), e.startTime)
        assertEquals(t(16), e.endTime)
        assertTrue(UncertainField.Time in r.uncertain)
    }

    @Test
    fun halfPastSwedishStyle() {
        // "halv fem" = 16:30
        assertEquals(t(16, 30), event("fika fre halv fem").startTime)
        assertEquals(t(16, 15), event("fika fre kvart över fyra").startTime)
    }

    @Test
    fun bareHourNextToWeekday() {
        val r = parse("fotboll tis 17")
        assertEquals(t(17), assertIs<QuickAddAction.CreateEvent>(r.primary).startTime)
        assertTrue(UncertainField.Time in r.uncertain)
    }

    @Test
    fun defaultDurationIsOneHour() {
        assertEquals(t(10), event("tandläkare fre 9:00").endTime)
    }

    @Test
    fun noTimeMeansAllDay() {
        val e = event("utflykt fredag")
        assertTrue(e.allDay)
        assertNull(e.startTime)
        assertTrue(EventTags.TRIP in e.tags)
    }

    @Test
    fun studyDayIsAllDay() {
        val e = event("studiedag fredag")
        assertTrue(e.allDay)
        assertTrue(EventTags.STUDY_DAY in e.tags)
    }

    // endregion

    // region Recurrence

    @Test
    fun everyWeekdayName() {
        val e = event("Ella simning varje onsdag 17")
        assertEquals(listOf(DayOfWeek.WEDNESDAY), e.recurrence?.byDay?.map { it.day })
        assertEquals(d("2026-10-14"), e.date)
        assertEquals(listOf("ella"), e.participantIds)
    }

    @Test
    fun everyOtherFriday() {
        val e = event("biblioteket varannan fredag")
        assertEquals(2, e.recurrence?.interval)
        assertEquals(listOf(DayOfWeek.FRIDAY), e.recurrence?.byDay?.map { it.day })
        assertEquals(d("2026-10-09"), e.date)
    }

    @Test
    fun pluralWeekdaysMeanWeekly() {
        val e = event("Simon simskola tisdagar och torsdagar 17:30")
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), e.recurrence?.byDay?.map { it.day })
        // Thursday is today and 17:30 has not passed yet.
        assertEquals(today, e.date)
    }

    @Test
    fun everyTuesdayAndThursday() {
        val e = event("innebandy varje tisdag och torsdag 18")
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), e.recurrence?.byDay?.map { it.day })
    }

    @Test
    fun everyOtherWeekUsesDateWeekday() {
        val e = event("städning lör varannan vecka 10")
        assertEquals(2, e.recurrence?.interval)
        assertEquals(listOf(DayOfWeek.SATURDAY), e.recurrence?.byDay?.map { it.day })
    }

    @Test
    fun weekdaysMondayToFriday() {
        val e = event("förskola vardagar 8-16")
        assertEquals(5, e.recurrence?.byDay?.size)
        assertEquals(t(8), e.startTime)
        assertEquals(t(16), e.endTime)
    }

    @Test
    fun everyDayAndEveryMonth() {
        assertEquals(Frequency.DAILY, event("medicin varje dag kl 8").recurrence?.freq)
        val monthly = event("styrelsemöte 15 okt varje månad 19")
        assertEquals(Frequency.MONTHLY, monthly.recurrence?.freq)
        assertEquals(listOf(15), monthly.recurrence?.byMonthDay)
    }

    @Test
    fun everyThirdWeek() {
        assertEquals(3, event("klippning var tredje vecka lör 10").recurrence?.interval)
    }

    // endregion

    // region People

    @Test
    fun possessiveNames() {
        assertEquals(listOf("ella"), event("Ellas dans ons 17").participantIds)
    }

    @Test
    fun multipleChildren() {
        assertEquals(listOf("ella", "simon"), event("Ella och Simon tandläkare fre 9").participantIds)
    }

    @Test
    fun namesAreCaseInsensitive() {
        assertEquals(listOf("simon"), event("simon fotboll tis 17").participantIds)
    }

    @Test
    fun aliasMorfar() {
        val h = handoff("morfar hämtar torsdag")
        assertEquals("bengt", h.personId)
        assertEquals(today, h.date)
    }

    @Test
    fun unknownNamesStayInTitle() {
        val e = event("Ella lekträff hos Vera tis 15")
        assertEquals("Lekträff hos Vera", e.title)
        assertEquals(listOf("ella"), e.participantIds)
    }

    // endregion

    // region Handoffs

    @Test
    fun iDropOffSimonFriday() {
        val h = handoff("jag lämnar Simon på fredag")
        assertEquals("sara", h.personId)
        assertEquals(HandoffKind.DropOff, h.kind)
        assertEquals(listOf("simon"), h.childIds)
        assertEquals(d("2026-10-09"), h.date)
    }

    @Test
    fun pickUpAtPreschoolWithTime() {
        val h = handoff("Johan hämtar Ella på fritids imorgon 16:30")
        assertEquals(listOf("ella"), h.childIds)
        assertEquals(t(16, 30), h.time)
    }

    @Test
    fun recurringPickUp() {
        val h = handoff("morfar hämtar varje torsdag")
        assertEquals(setOf(DayOfWeek.THURSDAY), h.recurringDays)
    }

    @Test
    fun imperativeWithChildIsHandoff() {
        val h = handoff("hämta Simon fre 15")
        assertEquals("sara", h.personId)
        assertEquals(listOf("simon"), h.childIds)
    }

    @Test
    fun hamtaUtPaketIsATaskNotAHandoff() {
        assertEquals("Hämta ut paketet", task("hämta ut paketet").title)
    }

    // endregion

    // region Tasks

    @Test
    fun simpleTask() {
        val tk = task("ring tandläkaren")
        assertEquals("Ring tandläkaren", tk.title)
        assertNull(tk.due)
    }

    @Test
    fun taskWithDeadline() {
        val tk = task("betala hyran senast fredag")
        assertEquals("Betala hyran", tk.title)
        assertEquals(d("2026-10-09"), tk.due)
    }

    @Test
    fun taskAssignedToPerson() {
        val tk = task("Johan boka tvättstugan")
        assertEquals("johan", tk.assigneeId)
        assertEquals("Boka tvättstugan", tk.title)
    }

    @Test
    fun presentForChildIsNotAssignedToChild() {
        val tk = task("köp present till Ella")
        assertNull(tk.assigneeId)
        assertEquals(listOf("ella"), tk.relatedPersonIds)
        assertEquals("Köp present", tk.title)
    }

    @Test
    fun rememberPrefixIsStripped() {
        assertEquals("Anmäla till simskolan", task("kom ihåg att anmäla till simskolan").title)
    }

    @Test
    fun unrecognisedTextBecomesTaskMarkedUncertain() {
        val r = parse("vinterdäcken")
        assertIs<QuickAddAction.CreateTask>(r.primary)
        assertTrue(UncertainField.Kind in r.uncertain)
    }

    // endregion

    // region Shopping

    @Test
    fun shoppingWithUnits() {
        val list = items("2 dl grädde, 500 g köttfärs, 1 burk krossade tomater")
        assertEquals(MeasureUnit.Dl, list[0].quantity?.unit)
        assertEquals("grädde", list[0].name)
        assertEquals(500.0, list[1].quantity?.value)
        assertEquals(MeasureUnit.G, list[1].quantity?.unit)
        assertEquals("krossade tomater", list[2].name)
    }

    @Test
    fun shoppingWithKopPrefixAndOch() {
        val list = items("köp mjölk och bröd")
        assertEquals(listOf("mjölk", "bröd"), list.map { it.name })
    }

    @Test
    fun singleGroceryWordGoesToList() {
        assertEquals(listOf("bananer"), items("bananer").map { it.name })
    }

    @Test
    fun decimalCommaIsNotASeparator() {
        val list = items("1,5 l mjölk, bröd")
        assertEquals(2, list.size)
        assertEquals(1.5, list[0].quantity?.value)
        assertEquals(MeasureUnit.L, list[0].quantity?.unit)
    }

    @Test
    fun newlineSeparatedList() {
        assertEquals(3, items("ägg\nsmör\nost").size)
    }

    @Test
    fun swedishNumberWordsAndFractions() {
        val list = items("två gurkor, ½ dl socker")
        assertEquals(2.0, list[0].quantity?.value)
        assertEquals(0.5, list[1].quantity?.value)
        assertEquals(MeasureUnit.Dl, list[1].quantity?.unit)
    }

    @Test
    fun kopPresentIsNotGroceries() {
        assertIs<QuickAddAction.CreateTask>(parse("köp present").primary)
    }

    // endregion

    // region Meals

    @Test
    fun mealWithExplicitMarker() {
        val m = meal("middag: lasagne på torsdag")
        assertEquals("Lasagne", m.title)
        assertEquals(today, m.date)
    }

    @Test
    fun lunchMarker() {
        assertEquals(MealSlot.Lunch, meal("lunch: soppa lör").slot)
    }

    @Test
    fun dishTomorrow() {
        val m = meal("köttbullar imorgon")
        assertEquals("Köttbullar", m.title)
        assertEquals(d("2026-10-09"), m.date)
    }

    @Test
    fun multiWordDish() {
        assertEquals("Pasta carbonara", meal("pasta carbonara onsdag").title)
    }

    @Test
    fun dishWithTimeIsAnEventNotAMeal() {
        assertIs<QuickAddAction.CreateEvent>(parse("pizza hos farmor lör 17").primary)
    }

    // endregion

    // region Locations, packing and extra clauses

    @Test
    fun capitalisedPlaceAfterPa() {
        val e = event("Simon match på Vallen lör 10")
        assertEquals("Vallen", e.location)
        assertEquals("Match", e.title)
    }

    @Test
    fun lowercaseWordAfterPaIsNotALocation() {
        assertEquals("", event("fotboll på konstgräset tis 17").location)
    }

    @Test
    fun packingClauseIsAddedToEvent() {
        val r = parse("Ella utflykt fredag, ta med matsäck")
        val e = assertIs<QuickAddAction.CreateEvent>(r.primary)
        assertEquals(listOf("Matsäck"), e.packingItems)
        assertEquals(1, r.actions.size)
    }

    @Test
    fun locationClause() {
        val e = event("kalas lör 14-16, hos Vera")
        assertEquals("hos Vera", e.location)
    }

    @Test
    fun linkedTaskDueTodayWhenEventIsToday() {
        val r = parse("kalas idag 17, köp blommor")
        assertEquals(today, assertIs<QuickAddAction.CreateTask>(r.actions[1]).due)
    }

    // endregion

    // region Robustness

    @Test
    fun emptyInput() {
        assertTrue(parse("   ").isEmpty)
    }

    @Test
    fun whitespaceIsNormalised() {
        assertEquals("Fotboll", event("  Simon    fotboll   tis   17:30  ").title)
    }

    @Test
    fun invalidDateIsIgnoredGracefully() {
        val r = parse("kalas 31/2 kl 14")
        assertFalse(r.isEmpty)
    }

    @Test
    fun confidenceDropsWithUncertainty() {
        val sure = parse("Simon fotboll tis 17:30 varje vecka")
        val unsure = parse("kalas lör 2-4")
        assertTrue(sure.confidence > unsure.confidence)
    }

    @Test
    fun englishBasics() {
        val e = event("swimming tomorrow at 17")
        assertEquals(d("2026-10-09"), e.date)
        assertEquals(t(17), e.startTime)
    }

    // endregion
}

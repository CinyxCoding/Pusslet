package se.pusslet.core.today

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import se.pusslet.core.conflicts.Conflict
import se.pusslet.core.meals.DishKeywords
import se.pusslet.core.meals.MealClash
import se.pusslet.core.model.Child
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolLunch
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.EventExpander
import se.pusslet.core.tasks.TaskScheduler
import se.pusslet.core.time.IsoWeek
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TodayPlannerTest {
    private val zone = TimeZone.of("Europe/Stockholm")
    private val tuesday = LocalDate(2026, 10, 13)
    private val sara = Member("sara", "Sara", Role.Parent, MemberColor.Sage)
    private val johan = Member("johan", "Johan", Role.Parent, MemberColor.Ocean)
    private val ella = Child("ella", "Ella", MemberColor.Rose, birthDate = LocalDate(2017, 3, 14))
    private val people = listOf(FamilyPerson.of(sara), FamilyPerson.of(johan), FamilyPerson.of(ella))

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private val events = listOf(
        Event(
            "school",
            "Skola & fritids",
            at(tuesday, 8, 15),
            at(tuesday, 16, 30),
            rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            participantIds = listOf("ella"),
            dropOff = Handoff("johan", LocalTime(8, 5)),
            pickUp = Handoff("sara"),
            tags = listOf("skola"),
        ),
        Event("meet1", "Styrelsemöte", at(tuesday, 16), at(tuesday, 17, 30), participantIds = listOf("sara")),
        Event("meet2", "Kundmöte", at(tuesday, 15, 45), at(tuesday, 17), participantIds = listOf("johan")),
        Event("dance", "Dans", at(tuesday.plusDays(1), 17, 15), at(tuesday.plusDays(1), 18, 15), participantIds = listOf("ella")),
        Event(
            "party",
            "Kalas hos Vera",
            at(tuesday.plusDays(4), 14),
            at(tuesday.plusDays(4), 16),
            participantIds = listOf("ella"),
            tags = listOf(EventTags.PARTY),
        ),
        Event(
            "study",
            "Studiedag",
            at(tuesday.plusDays(3), 0),
            at(tuesday.plusDays(4), 0),
            allDay = true,
            participantIds = listOf("ella"),
            tags = listOf(EventTags.STUDY_DAY),
        ),
    )

    private fun input(now: LocalTime = LocalTime(6, 50)) = TodayInput(
        today = tuesday,
        now = now,
        zone = zone,
        me = "sara",
        people = people,
        children = listOf(ella),
        occurrences = EventExpander.expand(events, tuesday, tuesday.plusDays(7), zone),
        packingRules = listOf(PackingRule("gym", "Gympapåse", "ella", PackingTrigger.Weekly(setOf(DayOfWeek.TUESDAY)))),
        mealWeeks = listOf(
            MealPlanWeek(
                tuesday.isoWeek,
            ).with(MealEntry(tuesday, title = "Fiskgratäng")).with(MealEntry(tuesday.plusDays(1), title = "Pasta pesto")),
        ),
        lunches = mapOf("ella" to listOf(SchoolLunch(tuesday, listOf("Fiskpinnar med potatis", "Vegetarisk soppa"), "manuell"))),
        tasks = listOf(
            FamilyTask(
                "present",
                "Köp present",
                category = TaskCategory.Gift,
                linkedEventId = "party",
                assigneeId = "sara",
                due = tuesday.plusDays(3),
            ),
            FamilyTask(
                "vacuum",
                "Dammsuga",
                kind = TaskKind.Chore,
                rrule = "FREQ=WEEKLY;BYDAY=TU",
                rotation = ChoreRotation(listOf("sara", "johan"), 1, tuesday.isoWeek),
            ),
        ),
    )

    @Test
    fun headerGreetingAndWeek() {
        val plan = TodayPlanner.build(input())
        assertEquals(IsoWeek(2026, 42), plan.week)
        assertEquals(Greeting.Morning, plan.greeting)
        assertEquals(Greeting.Evening, TodayPlanner.build(input(LocalTime(19, 0))).greeting)
    }

    @Test
    fun timelineShowsDropOffAndPickUpInsteadOfTheSchoolBlock() {
        val plan = TodayPlanner.build(input())
        val kinds = plan.timeline.map { item ->
            when (item) {
                is TimelineItem.HandoffItem -> "${item.kind}@${item.time}:${item.person?.name}"
                is TimelineItem.EventItem -> "event:${item.occurrence.event.title}"
            }
        }
        assertEquals(listOf("DropOff@08:05:Johan", "event:Kundmöte", "event:Styrelsemöte", "PickUp@16:30:Sara"), kinds)
    }

    @Test
    fun conflictCardWhenAssignedParentIsBusy() {
        val plan = TodayPlanner.build(input())
        val problem = plan.cards.filterIsInstance<ContextCard.Problem>().map { it.conflict }
        val busy = assertIs<Conflict.AssigneeBusy>(problem.single())
        assertEquals("sara", busy.personId)
        assertEquals(HandoffKind.PickUp, busy.kind)
    }

    @Test
    fun studyDayAndPartyWithoutPresentCards() {
        val cards = TodayPlanner.build(input()).cards
        val study = cards.filterIsInstance<ContextCard.StudyDay>().single()
        assertEquals(tuesday.plusDays(3), study.date)
        assertEquals(listOf("Ella"), study.children.map { it.name })
        val party = cards.filterIsInstance<ContextCard.PartyGift>().single()
        assertEquals("present", party.taskId)
    }

    @Test
    fun packingLunchDinnerAndClash() {
        val plan = TodayPlanner.build(input())
        assertEquals(listOf("Gympapåse"), plan.packing.single().items.map { it.text })
        assertEquals("Fiskgratäng", plan.dinner?.title)
        assertEquals(listOf("Fiskpinnar med potatis", "Vegetarisk soppa"), plan.lunches.single().dishes)
        // Fish for lunch (fiskpinnar) and fish for dinner (fiskgratäng).
        assertEquals(setOf("fisk", "fiskpinnar").intersect(plan.lunchDinnerClash.getValue("ella")).isNotEmpty(), true)
    }

    @Test
    fun myTasksIncludeRotatingChoreAndDueTask() {
        val plan = TodayPlanner.build(input())
        assertEquals(setOf("present", "vacuum"), plan.myTasks.map { it.task.id }.toSet())
    }

    @Test
    fun upcomingDaysSkipRoutineBlocks() {
        val plan = TodayPlanner.build(input())
        assertEquals(3, plan.upcoming.size)
        assertEquals(listOf("Dans"), plan.upcoming.first().events.map { it.event.title })
        assertEquals("Pasta pesto", plan.upcoming.first().dinner)
    }

    @Test
    fun choreRotationAlternatesWeekly() {
        val chore = FamilyTask("c", "Dammsuga", rotation = ChoreRotation(listOf("sara", "johan"), 1, IsoWeek(2026, 42)))
        assertEquals("sara", TaskScheduler.assigneeFor(chore, LocalDate(2026, 10, 13)))
        assertEquals("johan", TaskScheduler.assigneeFor(chore, LocalDate(2026, 10, 20)))
        assertEquals("sara", TaskScheduler.assigneeFor(chore, LocalDate(2026, 10, 27)))
        // Before the anchor and across New Year/week 53 the rotation keeps alternating.
        assertEquals("johan", TaskScheduler.assigneeFor(chore, LocalDate(2026, 10, 6)))
        assertEquals("johan", TaskScheduler.assigneeFor(chore, LocalDate(2026, 12, 28)))
        assertEquals("sara", TaskScheduler.assigneeFor(chore, LocalDate(2027, 1, 4)))
        val biweekly = chore.copy(rotation = ChoreRotation(listOf("sara", "johan"), 2, IsoWeek(2026, 42)))
        assertEquals("sara", TaskScheduler.assigneeFor(biweekly, LocalDate(2026, 10, 20)))
        assertEquals("johan", TaskScheduler.assigneeFor(biweekly, LocalDate(2026, 10, 27)))
    }

    @Test
    fun dishKeywordsAndClashes() {
        assertEquals(setOf("tacos"), MealClash.between(listOf("Tacobuffé"), "Fredagstacos"))
        assertEquals(
            setOf("köttbullar"),
            MealClash.between(listOf("Köttbullar med potatismos", "Vegetariska bullar"), "Köttbullar och mos"),
        )
        assertTrue(MealClash.between(listOf("Vegetarisk lasagne"), "Kycklinggryta").isEmpty())
        // Only the main lunch option counts.
        assertTrue(MealClash.between(listOf("Fiskpinnar", "Pasta med pesto"), "Pasta pesto").isEmpty())
        assertEquals(MainComponent.Fish, DishKeywords.mainComponent("Ugnslax med potatis"))
        assertEquals(MainComponent.MincedMeat, DishKeywords.mainComponent("tacos"))
    }
}

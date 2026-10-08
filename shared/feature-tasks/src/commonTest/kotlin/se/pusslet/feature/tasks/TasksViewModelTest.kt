package se.pusslet.feature.tasks

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Role
import se.pusslet.core.model.TaskKind
import se.pusslet.core.recurrence.RepeatOption
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.BENGT
import se.pusslet.testing.TestGraph.Companion.JOHAN
import se.pusslet.testing.TestGraph.Companion.SARA
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksViewModelTest {
    private fun vm(g: TestGraph) = TasksViewModel(g.tasks, g.family, g.sessions, g.ids, g.clock, g.zone)

    private fun editor(g: TestGraph) = TaskEditorViewModel(g.tasks, g.family, g.calendar, g.sessions, g.ids, g.clock, g.zone)

    @Test
    fun myListIsSortedIntoOverdueTodayComingUpAndWhenever() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val today = g.today
        g.tasks.save(FamilyTask("t-late", "Betala fritids", assigneeId = SARA, due = today.minusDays(2)))
        g.tasks.save(FamilyTask("t-today", "Ring tandläkaren", assigneeId = SARA, due = today))
        g.tasks.save(FamilyTask("t-later", "Anmäl till läger", assigneeId = SARA, due = today.plusDays(30)))
        g.tasks.save(FamilyTask("t-any", "Fixa cykeln", assigneeId = SARA))
        g.tasks.save(FamilyTask("t-johan", "Byt däck", assigneeId = JOHAN, due = today))
        g.tasks.save(FamilyTask("t-read", "Läsa 20 minuter", assigneeId = SARA, rrule = "FREQ=DAILY", due = today.minusDays(30)))
        val vm = vm(g)
        collectInBackground(vm.state)
        val s = vm.state.value
        assertEquals(listOf("t-late"), s.overdue.map { it.task.id }, "a missed daily habit is not piled up as overdue")
        assertEquals(setOf("t-today", "t-read"), s.todayItems.map { it.task.id }.toSet())
        assertEquals(listOf("t-later"), s.upcoming.map { it.task.id }, "a daily task is not repeated for two weeks")
        assertEquals(listOf("t-any"), s.undated.map { it.task.id })

        vm.setFilter(TaskFilter.All)
        assertTrue(vm.state.value.todayItems.any { it.task.id == "t-johan" })
    }

    @Test
    fun tickingOffRecurringAndOneOffTasks() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.tasks.save(FamilyTask("t-read", "Läsa", assigneeId = SARA, rrule = "FREQ=DAILY", due = g.today))
        g.tasks.save(FamilyTask("t-once", "Köp present", assigneeId = SARA, due = g.today))
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.state.value.todayItems.forEach { vm.setDone(it, true) }
        val s = vm.state.value
        assertTrue(s.todayItems.isEmpty())
        assertEquals(setOf("t-read", "t-once"), s.done.map { it.task.id }.toSet())
        assertEquals(SARA, g.tasks.get("t-read")?.completions?.get(g.today.toString())?.doneBy)
        assertEquals(true, g.tasks.get("t-once")?.done)
        assertTrue(s.upcoming.any { it.task.id == "t-read" && it.date == g.today.plusDays(1) }, "tomorrow's reading shows up")

        vm.setDone(s.done.first { it.task.id == "t-once" }, false)
        assertFalse(g.tasks.get("t-once")?.done ?: true)
    }

    @Test
    fun rotatingChoresFollowTheTurn() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val week = g.today.isoWeek
        g.tasks.save(
            FamilyTask(
                "c-vac",
                "Dammsuga",
                kind = TaskKind.Chore,
                rrule = "FREQ=WEEKLY;BYDAY=SA",
                due = week.monday,
                rotation = ChoreRotation(listOf(JOHAN, SARA), 1, week),
            ),
        )
        val vm = vm(g)
        collectInBackground(vm.state)
        assertTrue(vm.state.value.upcoming.none { it.task.id == "c-vac" }, "Johan's turn this week, not on Sara's list")
        vm.setFilter(TaskFilter.Chores)
        val o = vm.state.value.upcoming.single()
        assertEquals(JOHAN, o.assigneeId)
        assertEquals(week.monday.plusDays(5), o.date)
    }

    @Test
    fun quickAddMakesAnUndatedTaskForMe() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = vm(g)
        collectInBackground(vm.state)
        vm.quickAdd("fixa cykeln")
        val t = vm.state.value.undated.single().task
        assertEquals("Fixa cykeln", t.title)
        assertEquals(SARA, t.assigneeId)
    }

    @Test
    fun helpersOnlySeeSharedTasks() = viewModelTest {
        val g = TestGraph(role = Role.Caregiver, memberId = BENGT)
        g.seedFamily()
        g.tasks.save(FamilyTask("t1", "Hämta Simon", assigneeId = BENGT, due = g.today, sharedWithCaregivers = true))
        g.tasks.save(FamilyTask("t2", "Privat", assigneeId = BENGT, due = g.today))
        val vm = vm(g)
        collectInBackground(vm.state)
        assertEquals(listOf("t1"), vm.state.value.todayItems.map { it.task.id })
        assertFalse(vm.state.value.canEdit)
    }

    @Test
    fun newRotatingChoreIsSavedWithAnAnchorWeek() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val vm = editor(g)
        vm.load(null, chore = true)
        val s = vm.state.value
        assertEquals(TaskKind.Chore, s.draft.kind)
        assertEquals(RepeatOption.Weekly, s.draft.repeat)
        assertEquals(listOf("Bengt", "Johan", "Sara").sorted(), s.adults.map { it.name }.sorted())
        vm.edit { it.copy(title = "Ta ut soporna") }
        vm.toggleRotation(JOHAN)
        vm.toggleRotation(SARA)
        vm.addChecklistItem("Sopor")
        vm.addChecklistItem("Kartong")
        vm.save()
        assertTrue(vm.state.value.done)
        val saved = g.tasks.tasks.first().single()
        assertEquals("Ta ut soporna", saved.title)
        assertEquals(listOf(JOHAN, SARA), saved.rotation?.memberIds)
        assertEquals(g.today.isoWeek, saved.rotation?.anchorWeek)
        assertEquals(JOHAN, saved.assigneeId)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", saved.rrule)
        assertEquals(listOf("Sopor", "Kartong"), saved.checklist.map { it.text })
    }

    @Test
    fun editingKeepsHistoryAndATitleIsRequired() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.tasks.save(
            FamilyTask(
                "t1",
                "Läsa",
                rrule = "FREQ=DAILY",
                due = LocalDate(2026, 10, 1),
                completions = mapOf("2026-10-07" to se.pusslet.core.model.Completion(SARA, null)),
            ),
        )
        val vm = editor(g)
        vm.load("t1")
        assertEquals(RepeatOption.Daily, vm.state.value.draft.repeat)
        vm.edit { it.copy(title = " ") }
        vm.save()
        assertEquals(setOf(TaskError.TitleRequired), vm.state.value.errors)
        vm.edit { it.copy(title = "Läsa 20 minuter") }
        vm.save()
        val t = assertNotNull(g.tasks.get("t1"))
        assertEquals("Läsa 20 minuter", t.title)
        assertEquals(1, t.completions.size, "history is kept")
        vm.delete()
        assertNull(g.tasks.get("t1"))
    }

    @Test
    fun workloadComparesTheParents() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        val yesterday = g.today.minusDays(1)
        g.calendar.saveEvent(
            Event(
                "e1",
                "Förskola",
                LocalDateTime(yesterday, LocalTime(8, 0)),
                LocalDateTime(yesterday, LocalTime(16, 0)),
                dropOff = Handoff(SARA),
                pickUp = Handoff(BENGT),
            ),
        )
        g.tasks.save(FamilyTask("t1", "Köp present", done = true, doneBy = JOHAN, doneAt = g.clock.now()))
        val vm = WorkloadViewModel(g.tasks, g.family, g.calendar, g.clock, g.zone)
        collectInBackground(vm.state)
        val report = assertNotNull(vm.state.value.report)
        assertEquals(setOf(SARA, JOHAN), report.people.map { it.personId }.toSet(), "morfar helps but is not compared")
        assertEquals(1, report.people.first { it.personId == SARA }.dropOffs)
        assertEquals(1, report.people.first { it.personId == JOHAN }.tasks)
    }
}

package se.pusslet.feature.calendar

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.time.plusDays
import se.pusslet.feature.calendar.attachments.AttachImageViewModel
import se.pusslet.feature.calendar.attachments.AttachOutcome
import se.pusslet.testing.TestGraph
import se.pusslet.testing.TestGraph.Companion.SIMON
import se.pusslet.testing.collectInBackground
import se.pusslet.testing.recordMessages
import se.pusslet.testing.viewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AttachImageViewModelTest {
    /** The app scope runs eagerly here, so what happens after the screen closes can be checked right away. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.vm(g: TestGraph) = AttachImageViewModel(
        g.calendar,
        g.family,
        g.tasks,
        g.attachments,
        g.sessions,
        g.ids,
        g.clock,
        g.zone,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private suspend fun TestGraph.seedEvents() {
        val today = this.today
        calendar.saveEvent(
            Event(
                "school",
                "Förskola",
                LocalDateTime(today, LocalTime(8, 0)),
                LocalDateTime(today, LocalTime(16, 0)),
                rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
                participantIds = listOf(SIMON),
            ),
        )
        calendar.saveEvent(
            Event(
                "trip",
                "Skogsutflykt",
                LocalDateTime(today.plusDays(2), LocalTime(0, 0)),
                LocalDateTime(today.plusDays(3), LocalTime(0, 0)),
                allDay = true,
            ),
        )
    }

    @Test
    fun aPictureGoesWithAnEventAndStaysOnThePhone() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.seedEvents()
        g.files.add("letter.jpg")
        val vm = vm(g)
        vm.start("letter.jpg")
        collectInBackground(vm.state)
        val outcomes = recordMessages(vm.outcomes)

        // A daily school day is offered once, at its next occurrence.
        assertEquals(listOf("Förskola", "Skogsutflykt"), vm.state.value.events.map { it.event.title })
        vm.attachToEvent(vm.state.value.events.first { it.event.id == "school" })

        val saved = g.attachments.forOwner(AttachmentOwner.Event, "school").first().single()
        assertEquals("letter.jpg", saved.fileName)
        assertEquals(listOf(AttachOutcome.ToEvent("school", g.today)), outcomes)
        assertTrue(g.local.outboxFor("f-test", "attachments", saved.id).isEmpty(), "attachments never sync")
        assertTrue(g.local.listFamily("f-test").none { it.collection == "attachments" && it.pending })

        ViewModelStore().apply { put("vm", vm) }.clear()
        assertEquals(emptyList(), g.files.deleted, "a kept picture is not deleted when the screen closes")
    }

    @Test
    fun aPictureCanBecomeANote() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.files.add("receipt.jpg")
        val vm = vm(g)
        vm.start("receipt.jpg")
        val outcomes = recordMessages(vm.outcomes)
        vm.saveAsNote("  Veckobrev v. 41 ")
        val taskId = (outcomes.single() as AttachOutcome.ToNote).taskId
        assertEquals("Veckobrev v. 41", g.tasks.get(taskId)?.title)
        assertEquals("receipt.jpg", g.attachments.forOwner(AttachmentOwner.Task, taskId).first().single().fileName)
    }

    @Test
    fun leavingWithoutChoosingDeletesThePicture() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.files.add("oops.jpg")
        val vm = vm(g)
        vm.start("oops.jpg")
        ViewModelStore().apply { put("vm", vm) }.clear()
        assertEquals(listOf("oops.jpg"), g.files.deleted)
    }

    @Test
    fun cleanUpRemovesPicturesOfDeletedThingsAndOldStrayFiles() = viewModelTest {
        val g = TestGraph()
        g.seedFamily()
        g.tasks.save(FamilyTask("note", "Veckobrev"))
        g.files.add("kept.jpg")
        g.files.add("gone.jpg")
        g.files.add("stray-old.jpg", g.clock.now() - 2.days)
        g.files.add("stray-new.jpg")
        g.attachments.attach(AttachmentOwner.Task, "note", "kept.jpg")
        g.attachments.attach(AttachmentOwner.Event, "deleted-event", "gone.jpg")

        g.attachments.cleanUp(eventIds = emptySet(), taskIds = setOf("note"))

        assertEquals(setOf("kept.jpg", "stray-new.jpg"), g.files.stored.keys, "a fresh file may be a share in progress")
        assertEquals(listOf("kept.jpg"), g.attachments.all.first().map { it.fileName })
    }
}

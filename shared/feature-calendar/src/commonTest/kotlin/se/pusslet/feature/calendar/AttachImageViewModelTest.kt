package se.pusslet.feature.calendar

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.Event
import se.pusslet.data.repository.AttachmentFiles
import se.pusslet.data.repository.AttachmentRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.feature.calendar.attachments.AttachImageViewModel
import se.pusslet.feature.calendar.attachments.AttachOutcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** A picture shared to the app (ADR-049). "Today" is Tuesday 13 October 2026 (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachImageViewModelTest {
    private val today = LocalDate(2026, 10, 13)

    private class Files : AttachmentFiles {
        val deleted = mutableListOf<String>()

        override suspend fun delete(fileName: String) {
            deleted += fileName
        }

        override suspend fun deleteAll() = Unit

        override suspend fun list(): Map<String, Instant> = emptyMap()
    }

    private class Setup(val world: TestWorld = TestWorld()) {
        val files = Files()
        val tasks = TaskRepository(world.docs)
        val attachments = AttachmentRepository(world.docs, files, world.ids, world.clock)
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The app scope runs eagerly here, so what happens after the screen closes can be checked right away. */
    private fun TestScope.open(s: Setup, file: String): AttachImageViewModel {
        val w = s.world
        val vm = AttachImageViewModel(
            w.calendar,
            w.family,
            s.tasks,
            s.attachments,
            w.sessions,
            w.ids,
            w.clock,
            w.zone,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        vm.start(file)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private fun TestScope.outcomes(vm: AttachImageViewModel): List<AttachOutcome> {
        val list = mutableListOf<AttachOutcome>()
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch {
            vm.outcomes.collect { list += it }
        }
        return list
    }

    private suspend fun Setup.seedEvents() {
        world.calendar.saveEvent(
            Event(
                "school",
                "Förskola",
                LocalDateTime(today, LocalTime(8, 0)),
                LocalDateTime(today, LocalTime(16, 0)),
                rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            ),
        )
        world.calendar.saveEvent(
            Event(
                "trip",
                "Skogsutflykt",
                LocalDateTime(LocalDate(2026, 10, 15), LocalTime(0, 0)),
                LocalDateTime(LocalDate(2026, 10, 16), LocalTime(0, 0)),
                allDay = true,
            ),
        )
    }

    @Test
    fun aPictureGoesWithAnEventOnce() = runTest {
        val s = Setup()
        s.seedEvents()
        val vm = open(s, "letter.jpg")
        val outcomes = outcomes(vm)

        // A daily school day is offered once, at its next occurrence.
        assertEquals(listOf("Förskola", "Skogsutflykt"), vm.state.value.events.map { it.event.title })
        vm.attachToEvent(vm.state.value.events.first { it.event.id == "school" })

        assertEquals("letter.jpg", s.attachments.forOwner(AttachmentOwner.Event, "school").first().single().fileName)
        assertEquals(listOf<AttachOutcome>(AttachOutcome.ToEvent("school", today)), outcomes)
        ViewModelStore().apply { put("vm", vm) }.clear()
        assertEquals(emptyList(), s.files.deleted, "a kept picture is not deleted when the screen closes")
    }

    @Test
    fun aPictureCanBecomeANote() = runTest {
        val s = Setup()
        val vm = open(s, "receipt.jpg")
        val outcomes = outcomes(vm)
        vm.saveAsNote("  Veckobrev v. 41 ")
        val taskId = (outcomes.single() as AttachOutcome.ToNote).taskId
        assertEquals("Veckobrev v. 41", s.tasks.get(taskId)?.title)
        assertEquals("receipt.jpg", s.attachments.forOwner(AttachmentOwner.Task, taskId).first().single().fileName)
    }

    @Test
    fun leavingWithoutChoosingDeletesThePicture() = runTest {
        val s = Setup()
        val vm = open(s, "oops.jpg")
        ViewModelStore().apply { put("vm", vm) }.clear()
        assertEquals(listOf("oops.jpg"), s.files.deleted)
    }
}

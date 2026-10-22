package se.pusslet.feature.today

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Child
import se.pusslet.core.model.Event
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Member
import se.pusslet.core.model.Role
import se.pusslet.core.model.SickDay
import se.pusslet.core.model.VabPart
import se.pusslet.core.today.TimelineItem
import se.pusslet.data.sync.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Ella är sjuk idag" on Idag (ADR-047). Today is Tuesday 13 October 2026, 08:00 (see [TestWorld]). */
@OptIn(ExperimentalCoroutinesApi::class)
class SickDayTodayTest {
    private val tuesday = LocalDate(2026, 10, 13)
    private val monday = LocalDate(2026, 10, 12)

    private val school = Event(
        id = "e-school",
        title = "Skola",
        start = LocalDateTime(tuesday, LocalTime(8, 15)),
        end = LocalDateTime(tuesday, LocalTime(15, 0)),
        participantIds = listOf("c-ella"),
        pickUp = Handoff("m-johan", LocalTime(15, 0)),
        tags = listOf("skola"),
    )
    private val swimming = Event(
        id = "e-swim",
        title = "Simskola",
        start = LocalDateTime(tuesday, LocalTime(17, 30)),
        end = LocalDateTime(tuesday, LocalTime(18, 15)),
        participantIds = listOf("c-ella"),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(world: TestWorld): TodayViewModel {
        val vm = TestPhone(world, backgroundScope).todayViewModel()
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private suspend fun TestWorld.family() {
        family.saveMember(Member("m-sara", "Sara", Role.Parent))
        family.saveMember(Member("m-johan", "Johan", Role.Parent))
        family.saveMember(Member("m-bengt", "Bengt", Role.Caregiver))
        family.saveChild(Child("c-ella", "Ella"))
        calendar.saveEvent(school)
        calendar.saveEvent(swimming)
    }

    private suspend fun TestWorld.sickDay(day: SickDay) = docs.put(Collections.SickDays, day.id, day, SickDay.serializer())

    @Test
    fun reportingCancelsHerDayAndAsksWhoStaysHome() = runTest {
        val world = TestWorld()
        world.family()
        val vm = viewModel(world)
        assertTrue(vm.state.value.hero.any { it is HeroRow.Handoff }, "Johan picks Ella up at 15:00")

        vm.reportSick(listOf("c-ella"), tuesday)
        val card = vm.state.value.sick.cards.single()
        assertEquals("Ella", card.child.name)
        assertEquals(listOf("Skola", "Simskola"), card.cancelled.map { it.event.title })
        assertEquals(listOf("Sara", "Johan", "Bengt"), vm.state.value.sick.adults.map { it.name }, "parents first")
        assertTrue(vm.state.value.hero.none { it is HeroRow.Handoff }, "nobody has to pick her up")
        assertTrue(vm.state.value.plan?.timeline.orEmpty().filterIsInstance<TimelineItem.EventItem>().isEmpty(), "no swimming")

        vm.setHomeWith(card.day, "m-johan")
        val home = vm.state.value.sick.cards.single()
        assertEquals("Johan", home.homeWith?.name)
        assertTrue(home.day.vab, "a parent at home takes VAB")
        vm.setVabPart(home.day, VabPart.Half)
        vm.setAbsenceReported(home.day, true)
        assertEquals(VabPart.Half, vm.state.value.sick.cards.single().day.vabPart)
        assertTrue(vm.state.value.sick.cards.single().day.absenceReported)
        assertEquals(listOf("Johan" to 0.5), vm.state.value.sick.vabThisYear.map { it.first.name to it.second })

        vm.setHomeWith(vm.state.value.sick.cards.single().day, "m-bengt")
        assertTrue(!vm.state.value.sick.cards.single().day.vab, "morfar just stays home")

        vm.undoSick(vm.state.value.sick.cards.single().day)
        assertTrue(vm.state.value.sick.cards.isEmpty())
        assertTrue(vm.state.value.hero.any { it is HeroRow.Handoff }, "her day is back")
    }

    @Test
    fun theMorningAfterSheCanStaySickWithTheSamePersonAtHome() = runTest {
        val world = TestWorld()
        world.family()
        world.sickDay(SickDay(SickDay.idFor("c-ella", monday), "c-ella", monday, reportedBy = "m-sara", homeWith = "m-johan", vab = true))
        val vm = viewModel(world)

        val prompt = vm.state.value.sick.stillSick.single()
        assertEquals("Ella", prompt.child.name)
        assertTrue(vm.state.value.sick.vabToReport.isEmpty(), "Johan's VAB is his to report, not Sara's")

        vm.stillSick(prompt.day)
        val card = vm.state.value.sick.cards.single()
        assertEquals("Johan", card.homeWith?.name)
        assertTrue(card.day.vab)
        assertTrue(vm.state.value.sick.stillSick.isEmpty())
    }

    @Test
    fun wellAgainEndsTheQuestionAndAsksForTheVabReport() = runTest {
        val world = TestWorld()
        world.family()
        world.sickDay(SickDay(SickDay.idFor("c-ella", monday), "c-ella", monday, reportedBy = "m-sara", homeWith = "m-sara", vab = true))
        val vm = viewModel(world)

        vm.wellAgain(vm.state.value.sick.stillSick.single().day)
        assertTrue(vm.state.value.sick.stillSick.isEmpty())
        val report = vm.state.value.sick.vabToReport.single()
        assertEquals(1.0, report.report.total)

        vm.vabReported(report.report)
        assertTrue(vm.state.value.sick.vabToReport.isEmpty())
    }
}

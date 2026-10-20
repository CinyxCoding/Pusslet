package se.pusslet.feature.today.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import se.pusslet.core.helper.HelperWeekItem
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Event
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.model.Role
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DomainJson
import se.pusslet.data.sync.SyncStatus
import se.pusslet.data.usecase.FirstTimeHints
import se.pusslet.data.usecase.HandoffRequests
import se.pusslet.data.usecase.HintIds
import se.pusslet.feature.today.FakeCacheDao
import se.pusslet.feature.today.NoWeather
import se.pusslet.feature.today.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * "Så ser Bengt appen" on Sara's phone, with the demo family on Thursday 8 October 2026, 07:10 (the screenshots' clock),
 * plus a private event that would be his, a private board meeting and a subscription: the preview shows exactly what
 * CaregiverVisibility lets through and writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelperPreviewViewModelTest {
    private val thursday = LocalDate(2026, 10, 8)
    private val friday = LocalDate(2026, 10, 9)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    /** Private, although Bengt would pick up: he must not see it. */
    private val privateDentist = Event(
        "e-privat-tandlakare",
        "Ortodontist",
        at(thursday, 14),
        at(thursday, 14, 30),
        participantIds = listOf(DemoSeed.ELLA),
        pickUp = Handoff(DemoSeed.BENGT, LocalTime(14, 30)),
        sharedWithCaregivers = false,
    )
    private val board = Event("e-styrelse-fre", "Styrelsemöte", at(friday, 18), at(friday, 20), sharedWithCaregivers = false)

    /** From a subscription: "shared" on this phone, but subscriptions never reach a caregiver. */
    private val subscribed = Event(
        "sub-utvecklingssamtal",
        "Utvecklingssamtal",
        at(friday, 10),
        at(friday, 10, 30),
        participantIds = listOf(DemoSeed.ELLA),
        pickUp = Handoff(DemoSeed.BENGT, LocalTime(10, 30)),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Sara's phone after seeding the demo family; [calendar] also has the school's subscription. */
    private class Phone(val world: TestWorld, val calendar: CalendarRepository, val familyId: String)

    private suspend fun saraWithDemo(): Phone {
        val world = TestWorld(now = Instant.parse("2026-10-08T05:10:00Z"))
        DemoSeed(world.family, world.docs, world.clock).seed(thursday)
        val cache = FakeCacheDao()
        val calendar = CalendarRepository(world.docs, cache, world.prefs, NoDeviceCalendars, world.sessions)
        calendar.saveEvent(privateDentist)
        calendar.saveEvent(board)
        calendar.saveSubscription(CalendarSubscription("sub-skolan", "Björkängsskolan", "https://example.se/skolan.ics"))
        val body = DomainJson.encodeToString(ListSerializer(Event.serializer()), listOf(subscribed))
        cache.put(CacheEntryEntity(CalendarRepository.subscriptionCacheKey("sub-skolan"), body, fetchedAt = 0))
        world.tasks.save(FamilyTask("t-privat", "Boka om tandläkaren", assigneeId = DemoSeed.BENGT, due = thursday))
        return Phone(world, calendar, assertNotNull(world.sessions.session.value.familyId))
    }

    private fun TestScope.preview(phone: Phone, memberId: String = DemoSeed.BENGT): HelperPreviewViewModel {
        val w = phone.world
        val vm = HelperPreviewViewModel(w.family, phone.calendar, w.packing, w.tasks, w.clock, w.zone)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        vm.load(memberId)
        return vm
    }

    @Test
    fun thePreviewShowsOnlyWhatCaregiverVisibilityAllows() = runTest {
        val phone = saraWithDemo()
        // The data is there on Sara's phone …
        val all = phone.calendar.occurrences(thursday, friday, phone.world.zone).first().map { it.event.title }
        assertTrue(listOf("Ortodontist", "Styrelsemöte", "Utvecklingssamtal").all { it in all })

        val state = preview(phone).state.value

        // … but Bengt sees his Thursday pick-ups and Sara's question, nothing private and nothing subscribed.
        val plan = assertNotNull(state.home.plan)
        assertEquals("Bengt", state.member?.name)
        assertEquals(listOf("Simon", "Ella"), plan.missions.map { it.children.single().name })
        assertEquals(listOf(LocalTime(15, 30), LocalTime(15, 45)), plan.missions.map { it.time })
        val ask = plan.requests.single()
        assertEquals("Sara", ask.from?.name)
        assertEquals(HandoffKind.PickUp, ask.kind)
        assertEquals(friday, ask.date)
        assertEquals(LocalTime(16, 30), ask.time)
        val titles = plan.days.flatMap { day ->
            day.items.map { item ->
                when (item) {
                    is HelperWeekItem.Mission -> item.mission.occurrence.event.title
                    is HelperWeekItem.Event -> item.occurrence.event.title
                }
            }
        }
        listOf("Ortodontist", "Styrelsemöte", "Kundmöte", "Utvecklingssamtal").forEach { hidden ->
            assertTrue(hidden !in titles, "$hidden is not for Bengt")
        }
        // Only shared tasks: "Läsa 20 minuter" (shared, nobody's, on a day he picks up), not the private one to him.
        assertEquals(listOf("Läsa 20 minuter"), plan.tasks.map { it.task.title })
        // His first-time hint is not shown in a preview, and his own choices are not read: the caregiver's default.
        assertEquals(false, state.home.showHint)
        assertNull(state.home.shopping)
    }

    @Test
    fun itCountsWhatHeDoesNotSeeThisWeek() = runTest {
        val phone = saraWithDemo()

        val state = preview(phone).state.value

        // Monday 5 to Sunday 11 October: the demo's Styrelsemöte and Kundmöte, plus the two private ones added here. The
        // subscription is not the family's own, so it is not counted.
        assertEquals(4, state.hiddenThisWeek)
    }

    @Test
    fun itWorksBeforeBengtHasJoined() = runTest {
        val phone = saraWithDemo()
        assertNull(phone.world.family.members.first().single { it.id == DemoSeed.BENGT }.uid)

        val state = preview(phone).state.value

        assertEquals(2, assertNotNull(state.home.plan).missions.size)
    }

    @Test
    fun thePreviewWritesNothing() = runTest {
        val phone = saraWithDemo()
        val local = phone.world.local
        val documents = local.listFamily(phone.familyId)
        val outbox = local.nextOutbox(phone.familyId, Int.MAX_VALUE)
        val hints = FirstTimeHints(phone.world.prefs)

        val vm = preview(phone)
        assertNotNull(vm.state.value.home.plan)
        vm.load(DemoSeed.JOHAN)
        vm.load(DemoSeed.BENGT)

        assertEquals(documents, local.listFamily(phone.familyId))
        assertEquals(outbox, local.nextOutbox(phone.familyId, Int.MAX_VALUE))
        assertTrue(hints.shouldShow(HintIds.HELPER_HOME).first(), "Bengt's hint is his; a preview never dismisses it")
    }

    @Test
    fun thePreviewIsWhatBengtsOwnPhoneBuilds() = runTest {
        val phone = saraWithDemo()
        val w = phone.world
        val bengt = FixedSessionProvider(Session(familyId = phone.familyId, memberId = DemoSeed.BENGT, role = Role.Caregiver))
        val helper = HelperViewModel(
            w.family,
            phone.calendar,
            w.packing,
            w.tasks,
            w.shopping,
            w.prefs,
            NoWeather,
            bengt,
            HandoffRequests(phone.calendar, bengt, w.ids, w.clock),
            FirstTimeHints(w.prefs),
            flowOf(SyncStatus()),
            w.clock,
            w.zone,
        )
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { helper.state.collect {} }

        val preview = preview(phone).state.value

        assertEquals(helper.state.value.plan, preview.home.plan)
        assertEquals(helper.state.value.sections, preview.home.sections)
    }

    @Test
    fun anUnknownMemberShowsNothing() = runTest {
        val state = preview(saraWithDemo(), memberId = "m-borta").state.value

        assertNull(state.member)
    }
}

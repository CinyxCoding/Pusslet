package se.pusslet.feature.calendar.subscriptions

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.datetime.LocalDateTime
import se.pusslet.core.model.CalendarSubscription
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.data.db.CacheEntryEntity
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.RemovedSubscription
import se.pusslet.data.sources.PreviewResult
import se.pusslet.data.sources.SubscriptionError
import se.pusslet.data.sources.SubscriptionFeeds
import se.pusslet.data.sources.SubscriptionPreview
import se.pusslet.data.sources.SubscriptionStatus
import se.pusslet.feature.calendar.TestWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The download side is faked: what "Hämta och visa" and "Uppdatera nu" return, and when. */
private class FakeFeeds : SubscriptionFeeds {
    var result: PreviewResult = PreviewResult.Failed(SubscriptionError.Unreachable)
    val previews = ArrayList<String>()
    var refreshes = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun preview(url: String): PreviewResult {
        previews += url
        gate?.await()
        return result
    }

    override suspend fun refreshAllNow(): List<SubscriptionStatus> {
        refreshes++
        gate?.await()
        return emptyList()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val link = "webcal://skolan.example.se/kalender/3b.ics"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestWorld.withFamily(): TestWorld {
        family.saveMember(Member("m-sara", "Sara", Role.Parent, MemberColor.Sage, uid = "u"))
        family.saveChild(Child("c-ella", "Ella", color = MemberColor.Terracotta))
        return this
    }

    private fun TestScope.viewModel(world: TestWorld, feeds: SubscriptionFeeds = FakeFeeds()): SubscriptionsViewModel {
        val vm = SubscriptionsViewModel(world.calendar, world.family, feeds, world.sessions, world.ids, world.clock, world.zone)
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)).launch { vm.state.collect {} }
        return vm
    }

    private fun SubscriptionsViewModel.editor() = assertNotNull(state.value.editor)

    @Test
    fun addingFillsInTheCalendarsOwnNameAfterATest() = runTest {
        val world = TestWorld().withFamily()
        val feeds = FakeFeeds().apply { result = PreviewResult.Found(SubscriptionPreview("Björkängsskolan 3B", 12, emptyList())) }
        val vm = viewModel(world, feeds)

        vm.add()
        assertTrue(vm.editor().isNew)
        // A colour neither Sara nor Ella has.
        assertTrue(vm.editor().draft.color !in setOf(MemberColor.Sage, MemberColor.Terracotta))
        vm.setUrl(link)
        vm.testFetch()

        assertEquals(listOf(link), feeds.previews)
        assertIs<FeedTest.Found>(vm.editor().test)
        assertEquals("Björkängsskolan 3B", vm.editor().draft.name)

        vm.togglePerson("c-ella")
        vm.setColor(MemberColor.Ocean)
        vm.save()

        val saved = world.calendar.subscriptions.first().single()
        assertEquals(CalendarSubscription(saved.id, "Björkängsskolan 3B", link, listOf("c-ella"), MemberColor.Ocean, true), saved)
        assertNull(vm.state.value.editor)
        assertEquals("Björkängsskolan 3B", vm.state.value.rows.single().subscription.name)
    }

    @Test
    fun aTypedNameIsKeptAfterATest() = runTest {
        val feeds = FakeFeeds().apply { result = PreviewResult.Found(SubscriptionPreview("IFK F15", 3, emptyList())) }
        val vm = viewModel(TestWorld().withFamily(), feeds)
        vm.add()
        vm.setName("Ellas fotboll")
        vm.setUrl(link)

        vm.testFetch()

        assertEquals("Ellas fotboll", vm.editor().draft.name)
    }

    @Test
    fun theLinkAndTheNameAreCheckedBeforeTestingOrSaving() = runTest {
        val world = TestWorld().withFamily()
        val feeds = FakeFeeds()
        val vm = viewModel(world, feeds)
        vm.add()

        vm.save()
        assertEquals(UrlProblem.Required, vm.editor().urlProblem)
        assertTrue(vm.editor().nameMissing)

        vm.setUrl("skolan.example.se/3b.ics")
        vm.testFetch()
        assertEquals(UrlProblem.Invalid, vm.editor().urlProblem)
        assertEquals(emptyList(), feeds.previews)

        vm.setUrl("https://skolan.example.se/" + "a".repeat(2000))
        vm.save()
        assertEquals(UrlProblem.TooLong, vm.editor().urlProblem)

        vm.setUrl(link)
        vm.save()
        assertNull(vm.editor().urlProblem)
        assertTrue(vm.editor().nameMissing)
        assertEquals(emptyList(), world.calendar.subscriptions.first())
    }

    @Test
    fun aFailedTestSaysWhyAndANewLinkClearsIt() = runTest {
        val feeds = FakeFeeds().apply { result = PreviewResult.Failed(SubscriptionError.NotCalendar) }
        val vm = viewModel(TestWorld().withFamily(), feeds)
        vm.add()
        vm.setUrl(link)

        vm.testFetch()
        assertEquals(FeedTest.Failed(SubscriptionError.NotCalendar), vm.editor().test)

        vm.setUrl("$link?v=2")
        assertEquals(FeedTest.Idle, vm.editor().test)
    }

    @Test
    fun editingChangesTheLinkAndKeepsTheRest() = runTest {
        val world = TestWorld().withFamily()
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link, listOf("c-ella"), MemberColor.Plum, enabled = false))
        val vm = viewModel(world)

        vm.edit("s1")
        assertFalse(vm.editor().isNew)
        assertFalse(vm.editor().hasChanges)
        vm.setUrl("https://skolan.example.se/kalender/3c.ics")
        assertTrue(vm.editor().hasChanges)
        vm.save()

        val saved = assertNotNull(world.calendar.subscription("s1"))
        assertEquals("https://skolan.example.se/kalender/3c.ics", saved.url)
        assertEquals(CalendarSubscription("s1", "Skolan", saved.url, listOf("c-ella"), MemberColor.Plum, enabled = false), saved)
    }

    @Test
    fun deletingIsUndoneWithItsEvents() = runTest {
        val world = TestWorld().withFamily()
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link))
        val events = """[{"id":"ics:s1:a","title":"Idrott","start":"2026-10-14T13:00","end":"2026-10-14T14:00"}]"""
        world.calendar.saveSubscriptionStatus(SubscriptionStatus("s1", 1, Instant.parse("2026-10-13T05:00:00Z")))
        val cache = CalendarRepository.subscriptionCacheKey("s1")
        // TestWorld's fake cache is only reachable through the repository: put the parsed events there.
        world.calendar.restoreSubscription(
            RemovedSubscription(CalendarSubscription("s1", "Skolan", link), listOf(CacheEntryEntity(cache, events, 0))),
        )
        val vm = viewModel(world)

        vm.edit("s1")
        vm.delete()

        assertEquals(emptyList(), world.calendar.subscriptions.first())
        assertNull(vm.state.value.editor)
        val removal = assertNotNull(vm.state.value.removal)
        assertEquals("Skolan", removal.name)
        assertEquals(emptyList(), world.calendar.subscriptionEvents("s1").first())

        vm.undoRemove(removal.token)

        assertEquals(listOf("s1"), world.calendar.subscriptions.first().map { it.id })
        assertEquals(listOf("Idrott"), world.calendar.subscriptionEvents("s1").first().map { it.title })
        assertNotNull(world.calendar.subscriptionStatus("s1"))
        assertNull(vm.state.value.removal)
    }

    @Test
    fun aShownReceiptCannotBeUndoneLater() = runTest {
        val world = TestWorld().withFamily()
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link))
        val vm = viewModel(world)
        vm.edit("s1")
        vm.delete()
        val removal = assertNotNull(vm.state.value.removal)

        vm.removalShown(removal.token)
        vm.undoRemove(removal.token)

        assertEquals(emptyList(), world.calendar.subscriptions.first())
    }

    @Test
    fun rowsShowPeopleAndWhenTheyWereFetched() = runTest {
        val world = TestWorld().withFamily()
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link, listOf("c-ella")))
        world.calendar.saveSubscriptionStatus(SubscriptionStatus("s1", 4, Instant.parse("2026-10-13T05:12:00Z")))
        val vm = viewModel(world)

        val row = vm.state.value.rows.single()
        assertEquals(listOf("Ella"), row.people.map { it.name })
        assertEquals(LocalDateTime.parse("2026-10-13T07:12"), row.fetchedAt)
        assertEquals(row.fetchedAt?.date, vm.state.value.today)
    }

    @Test
    fun theSwitchAndUpdateNow() = runTest {
        val world = TestWorld().withFamily()
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link))
        val feeds = FakeFeeds().apply { gate = CompletableDeferred() }
        val vm = viewModel(world, feeds)

        vm.setEnabled("s1", false)
        assertFalse(assertNotNull(world.calendar.subscription("s1")).enabled)

        vm.refreshNow()
        assertTrue(vm.state.value.refreshing)
        vm.refreshNow()
        assertEquals(1, feeds.refreshes)
        feeds.gate?.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun onlyParentsManageSubscriptions() = runTest {
        val world = TestWorld(role = Role.Caregiver, memberId = "m-bengt")
        world.calendar.saveSubscription(CalendarSubscription("s1", "Skolan", link))
        val feeds = FakeFeeds()
        val vm = viewModel(world, feeds)

        vm.add()
        vm.edit("s1")
        vm.setEnabled("s1", false)
        vm.refreshNow()

        assertFalse(vm.state.value.canManage)
        assertEquals(emptyList(), vm.state.value.rows)
        assertNull(vm.state.value.editor)
        assertTrue(assertNotNull(world.calendar.subscription("s1")).enabled)
        assertEquals(0, feeds.refreshes)
    }
}

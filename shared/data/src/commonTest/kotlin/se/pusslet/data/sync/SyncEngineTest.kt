package se.pusslet.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import se.pusslet.core.model.Event
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.Role
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.time.IsoWeek
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    /** Per-device connectivity on top of the shared fake server. */
    private class DeviceLink(private val server: InMemoryRemoteStore) : RemoteStore {
        val online = MutableStateFlow(true)

        override fun listen(familyId: String, path: String, sinceServerTime: Long?, filter: RemoteFilter?): Flow<List<RemoteDoc>> =
            kotlinx.coroutines.flow.flow {
                online.first { it }
                server.listen(familyId, path, sinceServerTime, filter).collect { batch ->
                    online.first { it }
                    emit(batch)
                }
            }

        override suspend fun write(familyId: String, docPath: String, fields: Map<String, JsonElement?>, uid: String) {
            online.first { it }
            server.write(familyId, docPath, fields, uid)
        }
    }

    private class FakeClock(var now: Long = 1_700_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now++)
    }

    private inner class Device(server: InMemoryRemoteStore, uid: String, role: Role = Role.Parent) {
        val link = DeviceLink(server)
        val local = InMemorySyncLocalStore()
        val session = FixedSessionProvider(Session(familyId = FAMILY, memberId = "m-$uid", uid = uid, role = role))
        val docs = DocumentStore(local, session, FakeClock())
        val engine = SyncEngine(local, link, docs)

        fun start(scope: CoroutineScope) = engine.start(scope, FAMILY, session.session.value.uid!!, session.session.value.role)
    }

    private fun TestScope.settle() {
        repeat(30) {
            runCurrent()
            advanceTimeBy(50)
        }
        runCurrent()
    }

    private fun event(id: String, title: String, location: String = "", shared: Boolean = true) = Event(
        id = id,
        title = title,
        start = LocalDateTime.parse("2026-10-13T17:30"),
        end = LocalDateTime.parse("2026-10-13T18:30"),
        location = location,
        sharedWithCaregivers = shared,
    )

    @Test
    fun changesOnOneDeviceAppearOnTheOther() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        settle()

        a.docs.put(Collections.Events, "e1", event("e1", "Fotboll"), Event.serializer())
        settle()

        assertEquals("Fotboll", b.docs.get(Collections.Events, "e1", Event.serializer())?.title)
        assertEquals("sara", (server.snapshot(FAMILY, "events/e1")?.get(MetaFields.UPDATED_BY) as JsonPrimitive).content)
        assertEquals(0, a.local.nextOutbox(FAMILY, 10).size)
        assertEquals(false, a.local.get(FAMILY, "events", "e1")?.pending)
    }

    @Test
    fun offlineEditsAreQueuedAndSentWhenOnline() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        settle()

        a.link.online.value = false
        a.docs.put(Collections.Events, "e1", event("e1", "Simskola"), Event.serializer())
        settle()
        // Works locally while offline...
        assertEquals("Simskola", a.docs.get(Collections.Events, "e1", Event.serializer())?.title)
        assertEquals(true, a.local.get(FAMILY, "events", "e1")?.pending)
        // ...but has not reached the other device.
        assertNull(b.docs.get(Collections.Events, "e1", Event.serializer()))

        a.link.online.value = true
        settle()
        assertEquals("Simskola", b.docs.get(Collections.Events, "e1", Event.serializer())?.title)
    }

    @Test
    fun concurrentEditsOfDifferentFieldsBothSurvive() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        a.docs.put(Collections.Events, "e1", event("e1", "Fotboll"), Event.serializer())
        settle()

        a.link.online.value = false
        b.link.online.value = false
        a.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(title = "Fotbollsmatch") }
        b.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(location = "Vallen") }
        settle()
        a.link.online.value = true
        b.link.online.value = true
        settle()

        for (device in listOf(a, b)) {
            val e = assertNotNull(device.docs.get(Collections.Events, "e1", Event.serializer()))
            assertEquals("Fotbollsmatch", e.title)
            assertEquals("Vallen", e.location)
        }
    }

    @Test
    fun sameFieldConflictConvergesToLastServerWrite() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        a.docs.put(Collections.Events, "e1", event("e1", "Fotboll"), Event.serializer())
        settle()

        a.link.online.value = false
        b.link.online.value = false
        a.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(title = "A") }
        b.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(title = "B") }
        a.link.online.value = true
        settle()
        b.link.online.value = true
        settle()

        // B reached the server last, so B wins everywhere.
        assertEquals("B", a.docs.get(Collections.Events, "e1", Event.serializer())?.title)
        assertEquals("B", b.docs.get(Collections.Events, "e1", Event.serializer())?.title)
    }

    @Test
    fun softDeletesSync() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        a.docs.put(Collections.Events, "e1", event("e1", "Fotboll"), Event.serializer())
        settle()
        assertNotNull(b.docs.get(Collections.Events, "e1", Event.serializer()))

        b.docs.delete(Collections.Events, "e1")
        settle()
        assertNull(a.docs.get(Collections.Events, "e1", Event.serializer()))
        assertTrue(a.docs.observeAll(Collections.Events, Event.serializer()).first().isEmpty())
        assertNotNull(server.snapshot(FAMILY, "events/e1")?.get(MetaFields.DELETED_AT))
    }

    @Test
    fun mealPlanEntriesMergePerDay() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        settle()
        val week = IsoWeek(2026, 42)

        a.link.online.value = false
        b.link.online.value = false
        a.docs.put(
            Collections.MealPlans,
            week.key,
            MealPlanWeek(week).with(MealEntry(LocalDate(2026, 10, 12), title = "Tacos")),
            MealPlanWeek.serializer(),
        )
        b.docs.put(
            Collections.MealPlans,
            week.key,
            MealPlanWeek(week).with(MealEntry(LocalDate(2026, 10, 13), title = "Fisk")),
            MealPlanWeek.serializer(),
        )
        a.link.online.value = true
        b.link.online.value = true
        settle()

        for (device in listOf(a, b)) {
            val plan = assertNotNull(device.docs.get(Collections.MealPlans, week.key, MealPlanWeek.serializer()))
            assertEquals(setOf("Tacos", "Fisk"), plan.entries.values.map { it.title }.toSet())
        }
    }

    @Test
    fun nestedShoppingItemsSyncPerList() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        settle()

        a.docs.put(Collections.ShoppingLists, "l1", ShoppingList("l1", "Veckohandling"), ShoppingList.serializer())
        a.docs.put(Collections.ShoppingItems, "i1", ShoppingItem("i1", "l1", "Mjölk"), ShoppingItem.serializer())
        settle()

        assertNotNull(server.snapshot(FAMILY, "shoppingLists/l1/items/i1"))
        assertEquals("Mjölk", b.docs.get(Collections.ShoppingItems, "i1", ShoppingItem.serializer())?.name)
        assertEquals("l1", b.docs.get(Collections.ShoppingItems, "i1", ShoppingItem.serializer())?.listId)

        b.docs.update(Collections.ShoppingItems, "i1", ShoppingItem.serializer()) { it.copy(checked = true) }
        settle()
        assertEquals(true, a.docs.get(Collections.ShoppingItems, "i1", ShoppingItem.serializer())?.checked)
    }

    @Test
    fun pendingLocalEditIsNotOverwrittenByIncomingData() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        a.docs.put(Collections.Events, "e1", event("e1", "Fotboll"), Event.serializer())
        settle()

        a.link.online.value = false
        a.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(title = "Inställd") }
        // Another device changes a different field while A is offline.
        b.docs.update(Collections.Events, "e1", Event.serializer()) { it.copy(location = "Hallen") }
        settle()
        a.link.online.value = true
        // Before A's push completes the incoming change must keep A's pending title.
        runCurrent()
        assertEquals("Inställd", a.docs.get(Collections.Events, "e1", Event.serializer())?.title)
        settle()
        assertEquals("Hallen", a.docs.get(Collections.Events, "e1", Event.serializer())?.location)
        assertEquals("Inställd", b.docs.get(Collections.Events, "e1", Event.serializer())?.title)
    }

    @Test
    fun deniedWritesAreDroppedNotRetriedForever() = runTest {
        val server = InMemoryRemoteStore()
        server.writeRule = { _, path, _ -> !path.startsWith("icsSubscriptions") }
        val a = Device(server, "sara")
        a.start(backgroundScope)
        settle()
        a.docs.put(Collections.Events, "e1", event("e1", "Ok"), Event.serializer())
        a.docs.put(
            Collections.Subscriptions,
            "s1",
            se.pusslet.core.model.CalendarSubscription("s1", "Skolan", "https://x"),
            se.pusslet.core.model.CalendarSubscription.serializer(),
        )
        settle()
        assertEquals(0, a.local.nextOutbox(FAMILY, 10).size)
        assertNotNull(server.snapshot(FAMILY, "events/e1"))
        assertEquals(1, a.engine.status.value.rejectedWrites)
    }

    @Test
    fun caregiversOnlyReceiveSharedEvents() = runTest {
        val server = InMemoryRemoteStore()
        val parent = Device(server, "sara")
        val morfar = Device(server, "bengt", Role.Caregiver)
        parent.start(backgroundScope)
        morfar.start(backgroundScope)
        settle()
        parent.docs.put(Collections.Events, "e1", event("e1", "Hämtning torsdag", shared = true), Event.serializer())
        parent.docs.put(Collections.Events, "e2", event("e2", "Saras läkartid", shared = false), Event.serializer())
        settle()
        assertNotNull(morfar.docs.get(Collections.Events, "e1", Event.serializer()))
        assertNull(morfar.docs.get(Collections.Events, "e2", Event.serializer()))
    }

    @Test
    fun deviceThatStartsLaterCatchesUpFromScratch() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        a.start(backgroundScope)
        repeat(5) { i -> a.docs.put(Collections.Events, "e$i", event("e$i", "Händelse $i"), Event.serializer()) }
        settle()

        val c = Device(server, "johan")
        c.start(backgroundScope)
        settle()
        assertEquals(5, c.docs.getAll(Collections.Events, Event.serializer()).size)
    }

    private companion object {
        const val FAMILY = "f1"
    }
}

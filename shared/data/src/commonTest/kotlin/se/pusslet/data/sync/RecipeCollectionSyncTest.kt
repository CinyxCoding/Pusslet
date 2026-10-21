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
import kotlinx.serialization.json.JsonElement
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeCollection
import se.pusslet.core.model.Role
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Recipe collections sync per recipe: two parents filling "Kalas" offline both keep theirs; renaming keeps the recipes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeCollectionSyncTest {
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

    private class Device(server: InMemoryRemoteStore, uid: String) {
        val link = DeviceLink(server)
        val local = InMemorySyncLocalStore()
        val session = FixedSessionProvider(Session(familyId = FAMILY, memberId = "m-$uid", uid = uid, role = Role.Parent))
        val docs = DocumentStore(local, session, FakeClock())
        val recipes = RecipeRepository(docs)
        val engine = SyncEngine(local, link, docs)

        fun start(scope: CoroutineScope) = engine.start(scope, FAMILY, requireNotNull(session.session.value.uid), Role.Parent)
    }

    private fun TestScope.settle() {
        repeat(30) {
            runCurrent()
            advanceTimeBy(50)
        }
        runCurrent()
    }

    @Test
    fun twoParentsFillTheSameCollectionOffline() = runTest {
        val server = InMemoryRemoteStore()
        val a = Device(server, "sara")
        val b = Device(server, "johan")
        a.start(backgroundScope)
        b.start(backgroundScope)
        settle()
        a.recipes.save(Recipe("r-tacos", "Tacos"))
        a.recipes.save(Recipe("r-pesto", "Pasta med pesto"))
        a.recipes.saveCollection(RecipeCollection("c1", "Kalas", mapOf("r-soup" to true)))
        settle()

        a.link.online.value = false
        b.link.online.value = false
        a.recipes.setInCollection("c1", "r-tacos", inside = true)
        b.recipes.setInCollection("c1", "r-pesto", inside = true)
        b.recipes.setInCollection("c1", "r-soup", inside = false)
        a.link.online.value = true
        b.link.online.value = true
        settle()

        for (device in listOf(a, b)) {
            val c = assertNotNull(device.recipes.allCollections().singleOrNull())
            assertEquals(setOf("r-tacos", "r-pesto"), c.recipeIds.keys, "both additions and the removal survive")
        }

        b.recipes.saveCollection(assertNotNull(b.recipes.allCollections().single()).copy(name = "Barnkalas"))
        b.recipes.deleteCollection("c1")
        settle()
        assertEquals(emptyList(), a.recipes.allCollections())
        assertEquals(setOf("r-tacos", "r-pesto"), a.recipes.all().map { it.id }.toSet(), "deleting the collection keeps the recipes")
    }

    private companion object {
        const val FAMILY = "f1"
    }
}

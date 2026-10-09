package se.pusslet.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.core.util.SequentialIdGenerator
import se.pusslet.data.remote.LocalOnlyAuthRepository
import se.pusslet.data.remote.LocalOnlyFamilyRemote
import se.pusslet.data.repository.AttachmentRepository
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.LunchRepository
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.NoDeviceCalendars
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.repository.TaskRepository
import se.pusslet.data.repository.WishRepository
import se.pusslet.data.session.FixedSessionProvider
import se.pusslet.data.session.Session
import se.pusslet.data.sync.DocumentStore
import se.pusslet.data.sync.InMemorySyncLocalStore
import se.pusslet.data.usecase.AllowanceRepository
import se.pusslet.data.usecase.MealShopping
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.data.usecase.RecipeNutrition
import kotlin.time.Instant

/**
 * The real repositories over an in-memory document store: ViewModels are tested against the same code the app runs,
 * only the database, network and clock are fakes. Default time: Thursday 8 October 2026, 07:10 in Stockholm.
 */
class TestGraph(
    now: Instant = Instant.parse("2026-10-08T05:10:00Z"),
    role: Role = Role.Parent,
    memberId: String = SARA,
) {
    val zone: TimeZone = TimeZone.of("Europe/Stockholm")
    val clock = MutableClock(now)
    val sessions = FixedSessionProvider(Session(familyId = "f-test", memberId = memberId, uid = "u-$memberId", role = role))
    val ids = SequentialIdGenerator()
    val local = InMemorySyncLocalStore()
    val docs = DocumentStore(local, sessions, clock)
    val prefs = PreferencesRepository(FakePreferenceDao())
    val purchases = FakePurchaseLogDao()
    val family = FamilyRepository(docs, sessions, LocalOnlyFamilyRemote, LocalOnlyAuthRepository(), ids, clock)
    val calendar = CalendarRepository(docs, FakeCacheDao(), prefs, NoDeviceCalendars, sessions)
    val meals = MealRepository(docs)
    val recipes = RecipeRepository(docs)
    val shopping = ShoppingRepository(docs, purchases, sessions, clock)
    val tasks = TaskRepository(docs)
    val files = FakeAttachmentFiles(clock)
    val attachments = AttachmentRepository(docs, files, ids, clock)
    val wishes = WishRepository(docs, sessions, ids, clock)
    val allowances = AllowanceRepository(docs, clock)
    val packing = PackingRepository(docs)
    val lunchProvider = FakeLunchProvider()
    val lunches = LunchRepository(docs, lunchProvider, family)
    val foods = FakeFoodDatabase()
    val nutrition = RecipeNutrition(foods, recipes)
    val mealShopping = MealShopping(meals, recipes, shopping, family, sessions, ids, clock, zone)
    val products = FakeProductLookup()

    /** url -> HTML for the recipe importer; anything else is 404. */
    val pages = mutableMapOf<String, String>()
    val http = HttpClient(
        MockEngine { request ->
            val body = pages[request.url.toString()]
            if (body == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=utf-8"))
            }
        },
    )
    val importer = RecipeImporter(http, ids)

    val today: LocalDate get() = LocalDate(2026, 10, 8)

    /** Familjen Lind in miniature: Sara and Johan, morfar Bengt (helper), Ella 9 and Simon 4. */
    suspend fun seedFamily() {
        family.saveMember(Member(SARA, "Sara", color = MemberColor.Sage))
        family.saveMember(Member(JOHAN, "Johan", color = MemberColor.Ocean))
        family.saveMember(Member(BENGT, "Bengt", Role.Caregiver, MemberColor.Plum))
        family.saveChild(Child(ELLA, "Ella", birthDate = LocalDate(2017, 3, 2)))
        family.saveChild(Child(SIMON, "Simon", birthDate = LocalDate(2022, 5, 20)))
    }

    companion object {
        const val SARA = "m-sara"
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
        const val SIMON = "c-simon"
    }
}

/** runTest with Dispatchers.Main replaced, so viewModelScope runs on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
fun viewModelTest(block: suspend TestScope.() -> Unit): TestResult {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    // Main is reset only after runTest (which blocks on JVM and native) has cancelled the background collectors.
    try {
        return runTest { block() }
    } finally {
        Dispatchers.resetMain()
    }
}

/** Keeps a WhileSubscribed StateFlow hot for the rest of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectInBackground(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** Collects one-off messages (snackbars) into a list. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.recordMessages(flow: Flow<T>): List<T> {
    val list = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { list += it } }
    return list
}

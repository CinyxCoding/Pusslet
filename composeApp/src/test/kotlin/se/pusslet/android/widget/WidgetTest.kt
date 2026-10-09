package se.pusslet.android.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.Config
import se.pusslet.android.screenshots.TestEnvironment
import se.pusslet.core.model.MemberColor
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.ShoppingQuickAdd
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv")
class WidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun start(seed: Boolean = true) = runBlocking {
        TestEnvironment.start(context, seed = seed)
        if (seed) {
            withTimeout(15_000) {
                GlobalContext.get().get<SessionProvider>().session.first { it.hasFamily }
                GlobalContext.get().get<FamilyRepository>().children.first { kids -> kids.any { it.id == DemoSeed.SIMON } }
                GlobalContext.get().get<ShoppingRepository>().items.first { it.size >= 8 }
            }
        }
    }

    private fun today() = GlobalContext.get().let {
        TodayWidgetContent(it.get(), it.get(), it.get(), it.get(), it.get(), it.get(), it.get())
    }

    private fun shopping() = GlobalContext.get().let { ShoppingWidgetContent(it.get(), it.get(), it.get(), it.get(), it.get()) }

    @Test
    fun todayShowsTheDayFromTheSamePlanAsTheApp() = runBlocking {
        start()
        val state = today().load()
        assertEquals("Torsdag 8 oktober", state.ui.title)
        assertEquals("v. 41", state.ui.week)
        assertTrue(state.ui.lines.isNotEmpty(), "the demo Thursday has things on it")
        assertTrue(state.ui.lines.any { it.text.startsWith("Lämning") || it.text.startsWith("Hämtning") }, "${state.ui.lines}")
        assertTrue(state.ui.lines.all { it.eventId == null || it.date != null })
        val refresh = assertNotNull(state.refreshAt)
        assertTrue(refresh > TestEnvironment.now, "the widget wakes up for the next change, not before")
    }

    @Test
    fun beforeOnboardingTheWidgetsAskToOpenTheApp() = runBlocking {
        start(seed = false)
        assertFalse(today().load().ui.setUp)
        val list = shopping().load()
        assertFalse(list.setUp)
        assertEquals("Öppna Pusslet för att komma igång", list.title)
    }

    @Test
    fun shoppingShowsOpenItemsInTheStoresOrderAndQuickAddUsesTheAppsRules() = runBlocking {
        start()
        val ui = shopping().load()
        assertEquals("Veckohandling", ui.title)
        val names = ui.items.map { it.text }
        assertTrue("Mjölk · 2 l" in names, "$names")
        assertFalse(names.any { it.startsWith("Knäckebröd") }, "checked items are in the trolley, not on the widget")
        assertTrue(names.indexOf("Bananer") < names.indexOf("Mjölk · 2 l"), "fruit and vegetables come first at ICA: $names")

        // The widget's content follows the list: ticking something off shows up without a new widget session.
        val live = async { shopping().updates().first { ui -> ui.items.none { it.text == "Bananer" } } }
        val bananas = GlobalContext.get().get<ShoppingRepository>().items.first().first { it.name == "Bananer" }
        GlobalContext.get().get<ShoppingRepository>().setChecked(bananas, true)
        withTimeout(10_000) { live.await() }

        val quickAdd = GlobalContext.get().get<ShoppingQuickAdd>()
        val added = quickAdd.add(ui.listId!!, "mjölk, 3 citroner och tandkräm") // listId is set for a set-up list
        assertEquals(listOf("citroner", "tandkräm"), added.map { it.lowercase() }, "milk is already on the list")
        assertTrue(shopping().load().items.any { it.text.startsWith("Citroner") || it.text.startsWith("citroner") })
    }

    @Test
    fun todayContentRendersLinesAndWarnsWhenNobodyPicksUp() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 180.dp))
        val ui = TodayWidgetUi(
            title = "Torsdag 8 oktober",
            week = "v. 41",
            lines = listOf(
                WidgetLine("Nu", "Simning", listOf(MemberColor.Sage), eventId = "e1", date = LocalDate(2026, 10, 8)),
                WidgetLine("16:30", "Hämtning Simon – Ingen är satt än", warning = true, eventId = "e2", date = LocalDate(2026, 10, 8)),
                WidgetLine("Ska med", "Gympapåse"),
            ),
        )
        provideComposable { GlanceTheme(colors = PussletGlanceColors) { TodayContent(ui) } }
        onNode(hasText("Torsdag 8 oktober")).assertExists()
        onNode(hasText("Hämtning Simon – Ingen är satt än")).assertExists()
        onNode(hasText("Gympapåse")).assertExists()
    }

    @Test
    fun shoppingContentShowsItemsOrTheEmptyHint() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 260.dp))
        val ui = ShoppingWidgetUi(
            title = "Veckohandling",
            listId = "l",
            items = listOf(ShoppingWidgetItem("i1", "Mjölk · 2 l"), ShoppingWidgetItem("i2", "Bananer")),
            empty = "Listan är tom. Tryck + för att lägga till.",
            addLabel = "Lägg till på listan",
        )
        provideComposable { GlanceTheme(colors = PussletGlanceColors) { ShoppingContent(ui) } }
        onNode(hasText("Mjölk · 2 l")).assertExists()
        onNode(hasText("Listan är tom. Tryck + för att lägga till.")).assertDoesNotExist()
    }
}

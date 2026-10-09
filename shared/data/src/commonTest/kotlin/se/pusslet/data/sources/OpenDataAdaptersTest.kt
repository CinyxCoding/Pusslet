package se.pusslet.data.sources

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.Place
import se.pusslet.core.weather.WeatherMath
import se.pusslet.data.FakeCacheDao
import se.pusslet.data.FakeFoodDao
import se.pusslet.data.MockHttp
import se.pusslet.data.MockHttp.Companion.ok
import se.pusslet.data.readFixture
import se.pusslet.data.sync.DomainJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class OpenDataAdaptersTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")

    private class FixedClock(var now: Instant) : Clock {
        override fun now() = now
    }

    // region SMHI

    @Test
    fun smhiSnow1gFixtureParses() {
        val source = SmhiWeatherSource(CachedFetcher(MockHttp { ok("") }.client, FakeCacheDao()))
        val f = source.parse(readFixture("smhi_snow1g_stockholm.json"), 59.33, 18.07)
        assertEquals(81, f.points.size)
        val first = f.points.first()
        assertEquals(Instant.parse("2026-10-08T07:00:00Z"), first.time)
        assertEquals(11.7, first.temperature)
        assertEquals(3.6, first.windSpeed)
        assertEquals(6, first.symbol)
        assertEquals(1, first.precipitationType)
        val day = assertNotNull(WeatherMath.summarize(f, LocalDate(2026, 10, 8), stockholm))
        assertTrue(day.maxTemperature >= day.minTemperature)
    }

    @Test
    fun smhiIsCachedForAnHourAndFallsBackToStaleData() = runTest {
        var fail = false
        val http = MockHttp { if (fail) respondError(HttpStatusCode.ServiceUnavailable) else ok(readFixture("smhi_snow1g_stockholm.json")) }
        val clock = FixedClock(Instant.parse("2026-10-08T07:10:00Z"))
        val source = SmhiWeatherSource(CachedFetcher(http.client, FakeCacheDao(), clock), clock)
        val place = Place("Stockholm", 59.3293, 18.0686)

        assertNotNull(source.forecast(place))
        assertNotNull(source.forecast(place))
        assertEquals(1, http.requests.size, "second call within an hour must hit the cache")
        assertTrue(http.requests.single().contains("/snow1g/version/1/geotype/point/lon/18.07/lat/59.33/data.json"))

        clock.now = clock.now + 2.hours
        fail = true
        val stale = assertNotNull(source.forecast(place))
        assertTrue(stale.stale)
        assertEquals(81, stale.value.points.size)
    }

    // endregion

    // region Skolverket

    @Test
    fun skolverketListAndDetails() {
        val reg = SkolverketSchoolRegistry(CachedFetcher(MockHttp { ok("") }.client, FakeCacheDao()))
        val list = reg.parseList(readFixture("skolverket_school_units_0180.json"))
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.code.length == 8 })
        val details = reg.parseDetails(readFixture("skolverket_school_unit_43038662.json"))
        assertEquals("Färentuna skola", details.name)
        assertEquals("0125", details.municipalityCode)
        assertEquals("Ölstavägen 5", details.streetAddress)
        assertTrue("GR" in details.schoolTypes)
        assertNotNull(details.latitude)
    }

    @Test
    fun skolverketSearchFiltersLocallyByName() = runTest {
        val http = MockHttp { ok(readFixture("skolverket_school_units_0180.json")) }
        val reg = SkolverketSchoolRegistry(CachedFetcher(http.client, FakeCacheDao()))
        val all = assertNotNull(reg.search("0180")).value
        val first = all.first().name
        val hits = assertNotNull(reg.search("0180", first.take(4))).value
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().name.lowercase().startsWith(first.take(4).lowercase()))
        assertTrue(http.requests.first().contains("municipality_code=0180&status=AKTIV"))
        assertEquals(1, http.requests.size)
    }

    // endregion

    // region Skolmaten

    @Test
    fun skolmatenRssFixtureGivesOneLunchPerSchoolDay() {
        val lunches = RssLunchParser.parse(readFixture("skolmaten_week_vasaskolan.xml"), stockholm, "skolmaten.se")
        assertEquals(5, lunches.size)
        assertEquals(LocalDate(2026, 10, 5), lunches.first().date)
        assertEquals(listOf("Grillkorv serveras med potatismos"), lunches.first().dishes)
        assertEquals(listOf("Pasta med pesto", "Vegofärssås serveras med pasta"), lunches[1].dishes)
    }

    @Test
    fun skolmatenAcceptsWhateverParentsPaste() {
        assertEquals("vasaskolan", SkolmatenRssProvider.slugOf("https://skolmaten.se/vasaskolan"))
        assertEquals("vasaskolan", SkolmatenRssProvider.slugOf("https://skolmaten.se/vasaskolan/rss/weeks"))
        assertEquals("vasaskolan", SkolmatenRssProvider.slugOf("https://skolmaten.se/api/4/rss/week/vasaskolan?locale=sv"))
        assertEquals("vasaskolan", SkolmatenRssProvider.slugOf("Vasaskolan"))
        assertNull(SkolmatenRssProvider.slugOf("  "))
    }

    @Test
    fun skolmatenUsesNewEndpointAndRejectsHtml() = runTest {
        var html = false
        val http =
            MockHttp {
                if (html) {
                    ok(
                        "<!DOCTYPE html><div id=\"app\"></div>",
                        "text/html",
                    )
                } else {
                    ok(readFixture("skolmaten_week_vasaskolan.xml"), "application/rss+xml")
                }
            }
        val provider = SkolmatenRssProvider(CachedFetcher(http.client, FakeCacheDao()), stockholm)
        val result = assertNotNull(provider.lunches(LunchSource(LunchProviderType.Skolmaten, "https://skolmaten.se/vasaskolan")))
        assertEquals(5, result.value.size)
        assertEquals("https://skolmaten.se/api/4/rss/week/vasaskolan?locale=sv", http.requests.single())

        html = true
        val other = SkolmatenRssProvider(CachedFetcher(http.client, FakeCacheDao()), stockholm)
        assertNull(other.lunches(LunchSource(LunchProviderType.Skolmaten, "annanskola")))
    }

    // endregion

    // region Matilda

    @Test
    fun matildaFixtureGivesOneLunchPerDayWithSidesLabelled() {
        val lunches = MatildaLunchParser.parse(readFixture("matilda_week_gymnasium.xml"), "Matilda")
        assertEquals((5..9).map { LocalDate(2026, 10, it) }, lunches.map { it.date })
        assertEquals(listOf("Potatisbullar med lingonsylt", "Pytt i panna med rödbetor"), lunches.first().dishes)
        // Two items on Tuesday (the course and its sides) end up as one day.
        assertEquals(
            listOf(
                "Krämig pastagratäng med basilika",
                "Potatis- och purjolökssoppa",
                "Tillbehör: Mjukt bröd & pålägg serveras till soppan",
            ),
            lunches[1].dishes,
        )
        // "&" inside a dish (lower case after it) is not a new dish.
        assertEquals(
            listOf("Plant beef", "Klimatsmarta pannbiffar med stekt lök & gräddsås", "Tillbehör: Kokt potatis & rostad potatis"),
            lunches[3].dishes,
        )
        assertEquals("Matilda", lunches.first().source)
    }

    @Test
    fun matildaAcceptsTheMenuLinkParentsCopy() {
        val id = "685427abb1ee448fd5710ce1"
        assertEquals(id, MatildaRssProvider.distributorIdOf("https://menu.matildaplatform.com/meals/week/${id}_gymnasium"))
        assertEquals(
            id,
            MatildaRssProvider.distributorIdOf("https://menu.matildaplatform.com/sv/meals/${id}_gymnasium?startDate=2026-10-05"),
        )
        assertEquals(id, MatildaRssProvider.distributorIdOf("https://menu.matildaplatform.com/rss?distributorId=${id.uppercase()}&lang=sv"))
        assertEquals(id, MatildaRssProvider.distributorIdOf(" $id "))
        assertNull(MatildaRssProvider.distributorIdOf("https://menu.matildaplatform.com/meals/week/"))
        assertNull(MatildaRssProvider.distributorIdOf("Vasaskolan"))
    }

    @Test
    fun matildaUsesTheOfficialFeedAndOnlyForMatildaChildren() = runTest {
        val http = MockHttp { ok(readFixture("matilda_week_gymnasium.xml"), "application/rss+xml") }
        val menus = LunchMenus(
            listOf(
                SkolmatenRssProvider(CachedFetcher(http.client, FakeCacheDao()), stockholm),
                MatildaRssProvider(CachedFetcher(http.client, FakeCacheDao())),
            ),
        )
        val source =
            LunchSource(LunchProviderType.Matilda, "https://menu.matildaplatform.com/meals/week/685427abb1ee448fd5710ce1_gymnasium")
        val result = assertNotNull(menus.lunches(source))
        assertEquals(5, result.value.size)
        assertEquals(
            "https://menu.matildaplatform.com/rss?displayMode=week&distributorId=685427abb1ee448fd5710ce1&lang=sv",
            http.requests.single(),
        )
        assertNull(menus.lunches(LunchSource(LunchProviderType.Matilda, "ingen länk")))
        assertFalse(menus.supports(LunchSource(LunchProviderType.Manual)))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun anUnknownLunchProviderFromANewerAppReadsAsManual() {
        val child = DomainJson.decodeFromString(LunchSource.serializer(), """{"provider":"framtida","reference":"x"}""")
        assertEquals(LunchProviderType.Manual, child.provider)
    }

    // endregion

    // region Livsmedelsverket

    @Test
    fun livsmedelListAndNutrients() = runTest {
        val dao = FakeFoodDao()
        val http = MockHttp { req ->
            if (req.url.encodedPath.endsWith(
                    "/naringsvarden",
                )
            ) {
                ok(readFixture("livsmedel_123_naringsvarden.json"))
            } else {
                ok(readFixture("livsmedel_sample.json"))
            }
        }
        val db = LivsmedelsverketFoodDatabase(http.client, dao)
        assertTrue(db.ensureLoaded())
        assertEquals(62, dao.count())
        assertTrue(db.ensureLoaded())
        assertEquals(1, http.requests.size, "the list is only downloaded once")

        val hits = db.search("mjölk 3")
        assertEquals("Mjölk fett 3% berikad", hits.first().name)

        val milk = assertNotNull(db.nutrients(123))
        assertEquals(60.0, milk.energyKcal)
        assertEquals(250.0, milk.energyKj)
        assertEquals(3.6, milk.protein)
        assertEquals(3.0, milk.fat)
        assertEquals(4.6, milk.carbohydrates)
        assertEquals(0.0, milk.fiber)
        assertEquals(0.1, milk.salt)
        db.nutrients(123)
        assertEquals(2, http.requests.size, "nutrients are cached per version")
    }

    @Test
    fun livsmedelOnlyRewritesChangedVersions() = runTest {
        val dao = FakeFoodDao()
        val db = LivsmedelsverketFoodDatabase(MockHttp { ok(readFixture("livsmedel_sample.json")) }.client, dao)
        db.refresh()
        val before = dao.foods.getValue(123)
        dao.foods[123] = before.copy(name = "lokalt namn")
        db.refresh()
        assertEquals("lokalt namn", dao.foods.getValue(123).name, "unchanged version is not rewritten")
    }

    @Test
    fun ftsQueryBuildsPrefixTerms() {
        assertEquals("mjölk* 3*", LivsmedelsverketFoodDatabase.ftsQuery("Mjölk, 3%"))
        assertNull(LivsmedelsverketFoodDatabase.ftsQuery(" - "))
    }

    // endregion

    // region Open Food Facts

    @Test
    fun openFoodFactsFoundAndMissing() {
        val off = OpenFoodFactsLookup(CachedFetcher(MockHttp { ok("") }.client, FakeCacheDao()))
        val p = assertNotNull(off.parse(readFixture("off_product_7311041013663.json"), "7311041013663"))
        assertEquals("Snacks med baconsmak", p.name)
        assertEquals("First Price", p.brand)
        assertEquals("200 g", p.quantity)
        assertNull(off.parse(readFixture("off_product_not_found.json"), "7310090000419"))
    }

    @Test
    fun openFoodFactsRejectsGarbageBarcodes() = runTest {
        val http = MockHttp { ok("{}") }
        val off = OpenFoodFactsLookup(CachedFetcher(http.client, FakeCacheDao()))
        assertNull(off.product("abc"))
        assertTrue(http.requests.isEmpty())
    }

    // endregion

    @Test
    fun icsUrlNormalisation() {
        assertEquals("https://cal.example.com/a.ics", SubscriptionSync.normalizeUrl("webcal://cal.example.com/a.ics"))
        assertEquals("https://cal.example.com/a.ics", SubscriptionSync.normalizeUrl("http://cal.example.com/a.ics"))
        assertNull(SubscriptionSync.normalizeUrl("ftp://x"))
        assertFalse(SubscriptionSync.normalizeUrl("https://a b.se/x") != null)
    }

    @Test
    fun fetcherReturnsNullWhenNothingCachedAndOffline() = runTest {
        val fetcher = CachedFetcher(MockHttp { respond("", HttpStatusCode.InternalServerError) }.client, FakeCacheDao())
        assertNull(fetcher.get("k", "https://example.com", 1.hours))
    }
}

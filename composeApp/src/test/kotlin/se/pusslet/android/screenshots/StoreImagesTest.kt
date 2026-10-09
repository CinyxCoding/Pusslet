package se.pusslet.android.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.android.R
import se.pusslet.core.AppConfig
import se.pusslet.shell.PussletApp
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.onboarding_welcome_body

/**
 * Images for the Play store listing (docs/STORE_LISTING.md), made from the real app with the demo family:
 * phone screenshots at 1080×1920 (Play allows at most 2:1) in Swedish and English, the 512×512 icon and the
 * 1024×500 feature graphic. Not part of the normal test run – generate them with
 * `PUSSLET_STORE_IMAGES=1 ./gradlew :composeApp:recordRoborazziDemoDebug --tests "*StoreImages*"`.
 */
@OptIn(ExperimentalTestApi::class)
abstract class StoreScreenshots(private val lang: String, private val ui: Map<String, String>) {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("PUSSLET_STORE_IMAGES") == "1")

    @After
    fun tearDown() = TestEnvironment.stop()

    private fun text(key: String) = ui.getValue(key)

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 15_000)

    private fun launch() {
        TestEnvironment.start(ApplicationProvider.getApplicationContext(), seed = true)
        compose.setContent { PussletApp() }
        waitFor("Ella")
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/store/screenshots/$lang/$name.png")
    }

    @Test
    fun s1Today() {
        launch()
        snap("1_today")
    }

    @Test
    fun s2Week() {
        launch()
        compose.onNodeWithText(text("calendar")).performClick()
        waitFor(text("week"))
        snap("2_week")
    }

    @Test
    fun s3PickUp() {
        launch()
        compose.onNodeWithText(text("calendar")).performClick()
        waitFor(text("week"))
        compose.onAllNodesWithText("Skola", substring = true).onFirst().performClick()
        waitFor(text("handoffs"))
        snap("3_pick_up")
    }

    @Test
    fun s4Meals() {
        launch()
        compose.onNodeWithText(text("meals")).performClick()
        waitFor("Köttbullar med potatismos")
        snap("4_meals")
    }

    @Test
    fun s5StoreMode() {
        launch()
        compose.onNodeWithText(text("shopping")).performClick()
        waitFor("Bananer")
        compose.onNodeWithText(text("storeMode")).performClick()
        waitFor("Bananer")
        snap("5_store_mode")
    }

    @Test
    fun s6KidMode() {
        launch()
        compose.onNodeWithText(text("more")).performClick()
        waitFor(text("kidMode"))
        compose.onNodeWithText(text("kidMode")).performClick()
        waitFor(text("kidPick"))
        compose.onNodeWithText("Ella").performClick()
        waitFor("Duka av efter middagen")
        snap("6_kid_mode")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w360dp-h640dp-xxhdpi")
class StoreImagesSwedishTest :
    StoreScreenshots(
        "sv",
        mapOf(
            "calendar" to "Kalender",
            "week" to "Vecka 41",
            "handoffs" to "Lämning och hämtning",
            "meals" to "Mat",
            "shopping" to "Handla",
            "storeMode" to "Butiksläge",
            "more" to "Mer",
            "kidMode" to "Barnläge",
            "kidPick" to "Vem ska använda telefonen?",
        ),
    )

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w360dp-h640dp-xxhdpi")
class StoreImagesEnglishTest :
    StoreScreenshots(
        "en",
        mapOf(
            "calendar" to "Calendar",
            "week" to "Week 41",
            "handoffs" to "Drop-off and pick-up",
            "meals" to "Meals",
            "shopping" to "Shopping",
            "storeMode" to "Store mode",
            "more" to "More",
            "kidMode" to "Child view",
            "kidPick" to "Who will use the phone?",
        ),
    )

/**
 * The 512×512 icon and the 1024×500 feature graphic, drawn from the launcher icon. The composition uses 1 px per dp on a
 * 1600 px wide screen, so the images get their exact pixel sizes.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w800dp-h800dp-xhdpi")
class StoreImagesGraphicsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("PUSSLET_STORE_IMAGES") == "1")

    private val sand = Color(0xFFF4E2C6)
    private val ink = Color(0xFF2B2A26)

    @Test
    fun icon() {
        pixels {
            Box(Modifier.requiredSize(ICON.dp).background(sand).testTag("icon"), contentAlignment = Alignment.Center) {
                // The launcher shows the middle 72 of the 108-unit adaptive icon; the store icon shows the same.
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize((ICON * 108 / 72).dp))
            }
        }
        compose.onNodeWithTag("icon").captureRoboImage("../docs/store/icon_512.png")
    }

    @Test
    @Config(qualifiers = "sv-w800dp-h800dp-xhdpi")
    fun featureGraphicSwedish() = featureGraphic("sv")

    @Test
    @Config(qualifiers = "en-w800dp-h800dp-xhdpi")
    fun featureGraphicEnglish() = featureGraphic("en")

    private fun featureGraphic(lang: String) {
        pixels {
            Row(
                Modifier.requiredSize(FEATURE_W.dp, FEATURE_H.dp).testTag("feature").background(sand).padding(horizontal = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(48.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(330.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(AppConfig.APP_NAME, color = ink, fontSize = 84.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.onboarding_welcome_body), color = ink, fontSize = 30.sp, lineHeight = 40.sp)
                }
            }
        }
        compose.onNodeWithTag("feature").captureRoboImage("../docs/store/feature_graphic_$lang.png")
    }

    private fun pixels(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, 1f), content = content)
    }

    private companion object {
        const val ICON = 512
        const val FEATURE_W = 1024
        const val FEATURE_H = 500
    }
}

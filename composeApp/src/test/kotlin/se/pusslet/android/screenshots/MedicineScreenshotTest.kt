package se.pusslet.android.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import se.pusslet.core.medicine.HealthLinks
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Role
import se.pusslet.feature.today.medicine.MedicineActions
import se.pusslet.feature.today.medicine.MedicineModel
import se.pusslet.feature.today.medicine.MedicineScreen
import se.pusslet.feature.today.medicine.MedicineUiState
import se.pusslet.ui.theme.PussletTheme
import kotlin.test.assertEquals

/** "Medicin och feber" (ADR-050) with a course and a fever, and where its links go (ADR-061). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MedicineScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = TimeZone.of("Europe/Stockholm")
    private val today = LocalDate(2026, 10, 29)
    private val ella = FamilyPerson("ella", "Ella", MemberColor.Terracotta, isChild = true)
    private val sara = FamilyPerson("sara", "Sara", MemberColor.Ocean, isChild = false, role = Role.Parent)
    private val course = MedicineCourse(
        id = "course",
        childId = ella.id,
        medicine = "Kåvepenin",
        dose = "5 ml",
        times = listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0)),
        start = LocalDate(2026, 10, 28),
        days = 7,
    )
    private val dose = MedicineEntry(
        id = "dose",
        childId = ella.id,
        at = LocalDateTime(2026, 10, 29, 8, 5).toInstant(zone),
        medicine = "Kåvepenin",
        dose = "5 ml",
        by = sara.id,
        courseId = course.id,
        slot = LocalDateTime(2026, 10, 29, 8, 0),
    )
    private val fever = MedicineEntry(
        id = "fever",
        childId = ella.id,
        at = LocalDateTime(2026, 10, 29, 7, 50).toInstant(zone),
        temperature = 38.6,
        by = sara.id,
    )
    private val people = listOf(ella, sara).associateBy { it.id }
    private val state = MedicineUiState(
        loading = false,
        canEdit = true,
        children = listOf(ella),
        childId = ella.id,
        courses = MedicineModel.courses(ella.id, today, listOf(course), listOf(dose, fever), people),
        log = MedicineModel.log(ella.id, listOf(dose, fever), people, zone),
        lastTemperature = MedicineModel.row(fever, people, zone),
        usual = listOf("Kåvepenin"),
        today = today,
    )

    @Test
    fun medicine() {
        compose.setContent { PussletTheme { MedicineScreen(state, MedicineActions()) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/medicine.png")
    }

    @Test
    fun theLinksGoToFassAnd1177() {
        val opened = mutableListOf<String>()
        val called = mutableListOf<String>()
        compose.setContent {
            PussletTheme { MedicineScreen(state, MedicineActions(onOpenLink = { opened += it }, onCall = { called += it })) }
        }

        compose.onNodeWithText("Läs bipacksedeln på FASS").performClick()
        compose.onNodeWithText("Feber hos barn på 1177").performClick()
        compose.onNodeWithText("Ring 1177").performClick()

        assertEquals(listOf(HealthLinks.fass("Kåvepenin"), HealthLinks.FEVER_IN_CHILDREN), opened)
        assertEquals(listOf("1177"), called)
    }
}

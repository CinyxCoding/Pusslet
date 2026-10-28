package se.pusslet.core.medicine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import se.pusslet.core.model.MedicineCourse
import se.pusslet.core.model.MedicineEntry
import se.pusslet.core.model.Role
import se.pusslet.core.notify.NotificationKind
import se.pusslet.core.notify.NotificationPayload
import se.pusslet.core.notify.NotifyFixtures.ella
import se.pusslet.core.notify.NotifyFixtures.input
import se.pusslet.core.notify.NotifyFixtures.sara
import se.pusslet.core.notify.NotifyFixtures.wednesday
import se.pusslet.core.notify.NotifyFixtures.zone
import se.pusslet.core.notify.ReminderPlanner
import se.pusslet.core.time.plusDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The medicine and fever log (ADR-050). [wednesday] is 21 October 2026. */
class MedicineRulesTest {
    private val penicillin = MedicineCourse(
        id = "c1",
        childId = ella.id,
        medicine = "Kåvepenin",
        dose = "5 ml",
        times = listOf(LocalTime(20, 0), LocalTime(8, 0), LocalTime(14, 0)),
        start = wednesday,
        days = 10,
    )

    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant = LocalDateTime(date, LocalTime(h, m)).toInstant(zone)

    private fun given(date: LocalDate, h: Int) = MedicineEntry(
        id = "g-$date-$h",
        childId = ella.id,
        at = at(date, h, 5),
        medicine = "Kåvepenin",
        dose = "5 ml",
        by = sara.id,
        courseId = "c1",
        slot = LocalDateTime(date, LocalTime(h, 0)),
    )

    @Test
    fun aCourseRunsItsDaysAtItsTimes() {
        assertEquals(wednesday.plusDays(9), MedicineRules.lastDay(penicillin))
        assertFalse(MedicineRules.isActive(penicillin, wednesday.plusDays(-1)))
        assertTrue(MedicineRules.isActive(penicillin, wednesday.plusDays(9)))
        assertFalse(MedicineRules.isActive(penicillin, wednesday.plusDays(10)))
        assertFalse(MedicineRules.isActive(penicillin.copy(stopped = true), wednesday))

        val slots = MedicineRules.slots(penicillin, wednesday, listOf(given(wednesday, 8)))
        assertEquals(listOf(8, 14, 20), slots.map { it.at.hour }, "sorted by time")
        assertEquals(listOf(true, false, false), slots.map { it.done })
        assertEquals(30, MedicineRules.totalDoses(penicillin))
        assertEquals(5, MedicineRules.doseNumber(penicillin, LocalDateTime(wednesday.plusDays(1), LocalTime(14, 0))))
    }

    @Test
    fun theParentsAreRemindedOfEachDoseNotYetGiven() {
        val morning = at(wednesday, 7)
        val base = input(now = morning).base.copy(medicineCourses = listOf(penicillin), medicineLog = listOf(given(wednesday, 8)))
        val planned = ReminderPlanner.plan(input(now = morning).copy(base = base)).filter { it.key.startsWith("medicine:") }

        assertEquals(at(wednesday, 14), planned.first().at, "08:00 was given, so 14:00 is the first")
        assertEquals(NotificationKind.Reminder, planned.first().kind)
        assertEquals(NotificationPayload.MedicineDose("Ella", "Kåvepenin", "5 ml"), planned.first().payload)
        assertTrue(planned.all { it.userTimed }, "a dose comes at its time, also in quiet hours")
        assertEquals(5, planned.size, "14 and 20 today, 8, 14 and 20 tomorrow within 48 hours")

        val caregiver = ReminderPlanner.plan(input(now = morning, role = Role.Caregiver).copy(base = base))
        assertTrue(caregiver.none { it.key.startsWith("medicine:") }, "the log is the parents' own")
    }

    @Test
    fun theLogAnswersWhatWasGivenAndTheLatestTemperature() {
        val alvedon = MedicineEntry("a", ella.id, at(wednesday, 6), medicine = "Alvedon", dose = "250 mg", by = sara.id)
        val later = alvedon.copy(id = "b", at = at(wednesday, 12), medicine = "alvedon ")
        val fever = MedicineEntry("t", ella.id, at(wednesday, 7), temperature = 38.6)
        val normal = fever.copy(id = "t2", at = at(wednesday, 13), temperature = 37.2)
        val entries = listOf(alvedon, later, fever, normal)

        assertEquals("b", MedicineRules.lastDose(entries, ella.id, "Alvedon")?.id, "any case and spacing")
        assertEquals("t2", MedicineRules.lastTemperature(entries, ella.id)?.id)
        assertTrue(MedicineRules.isFever(fever))
        assertFalse(MedicineRules.isFever(normal))
        assertEquals(listOf("b", "t2", "t", "a").sorted(), MedicineRules.log(entries, ella.id).map { it.id }.sorted())
        assertEquals("t2", MedicineRules.log(entries, ella.id).first().id, "newest first")
        assertEquals(listOf("Alvedon"), MedicineRules.usual(entries, emptyList()))
        assertEquals("250 mg", MedicineRules.usualDose(entries, "ALVEDON"))
    }

    @Test
    fun temperaturesAreReadTheSwedishWayAndKeptToABody() {
        assertEquals(38.4, MedicineRules.parseTemperature("38,4"))
        assertEquals(38.4, MedicineRules.parseTemperature(" 38.4 °C"))
        assertNull(MedicineRules.parseTemperature("384"))
        assertNull(MedicineRules.parseTemperature("hög"))
    }

    @Test
    fun theLogIsKeptForNinetyDays() {
        val old = MedicineEntry("old", ella.id, at(wednesday.plusDays(-91), 8), medicine = "Alvedon")
        val recent = old.copy(id = "new", at = at(wednesday.plusDays(-10), 8))
        val keepFrom = at(wednesday, 8) - kotlin.time.Duration.parse("90d")
        assertEquals(listOf(old), MedicineRules.expired(listOf(old, recent), keepFrom))
        val ended = penicillin.copy(start = wednesday.plusDays(-120))
        assertEquals(listOf(ended), MedicineRules.expiredCourses(listOf(ended, penicillin), wednesday.plusDays(-90)))
    }

    @Test
    fun defaultTimesSpreadOverTheDay() {
        assertEquals(listOf(LocalTime(8, 0), LocalTime(20, 0)), MedicineRules.defaultTimes(2))
        assertEquals(4, MedicineRules.defaultTimes(4).size)
        assertEquals(MedicineCourse.MAX_TIMES, MedicineRules.defaultTimes(9).size)
    }
}

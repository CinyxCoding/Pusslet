package se.pusslet.data.sources

import kotlinx.datetime.TimeZone
import se.pusslet.core.holidays.HolidayId
import se.pusslet.core.holidays.SwedishHolidays
import se.pusslet.core.ics.IcsParser
import se.pusslet.data.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-checks the local holiday calculation against Google's public "Helgdagar i Sverige" ICS feed
 * (11 years, parsed with our own ICS parser). Every holiday Google lists must be computed on the same date.
 */
class HolidayCrossCheckTest {
    private val names = mapOf(
        "Nyårsdagen" to HolidayId.NewYearsDay,
        "Trettondagsafton" to HolidayId.TwelfthNight,
        "Trettondedag jul" to HolidayId.Epiphany,
        "Alla hjärtans dag" to HolidayId.ValentinesDay,
        "Skärtorsdag" to HolidayId.MaundyThursday,
        "Långfredagen" to HolidayId.GoodFriday,
        "Påskafton" to HolidayId.EasterEve,
        "Påskdagen" to HolidayId.EasterSunday,
        "Annandag påsk" to HolidayId.EasterMonday,
        "Valborgsmässoafton" to HolidayId.WalpurgisNight,
        "Första maj" to HolidayId.MayDay,
        "Kristi himmelsfärdsdag" to HolidayId.AscensionDay,
        "Mors dag" to HolidayId.MothersDay,
        "Pingstafton" to HolidayId.WhitsunEve,
        "Pingstdagen" to HolidayId.WhitSunday,
        "Sveriges nationaldag" to HolidayId.NationalDay,
        "Midsommarafton" to HolidayId.MidsummerEve,
        "Midsommardagen" to HolidayId.MidsummerDay,
        "Allhelgonaafton" to HolidayId.AllSaintsEve,
        "Alla helgons dag" to HolidayId.AllSaintsDay,
        "Fars dag" to HolidayId.FathersDay,
        "Första söndagen i advent" to HolidayId.FirstAdvent,
        "Andra söndagen i advent" to HolidayId.SecondAdvent,
        "Tredje söndagen i advent" to HolidayId.ThirdAdvent,
        "Fjärde söndagen i advent" to HolidayId.FourthAdvent,
        "Julafton" to HolidayId.ChristmasEve,
        "Juldagen" to HolidayId.ChristmasDay,
        "Annandag jul" to HolidayId.BoxingDay,
        "Nyårsafton" to HolidayId.NewYearsEve,
        "Sommartid" to HolidayId.DaylightSavingStart,
        "Vintertid" to HolidayId.DaylightSavingEnd,
    )

    @Test
    fun everyPublishedHolidayMatchesOurCalculation() {
        val cal = IcsParser().parse(readFixture("ics_google_swedish_holidays.ics"), "g:", TimeZone.of("Europe/Stockholm"))
        assertTrue(cal.events.size > 300)
        var checked = 0
        for (event in cal.events) {
            val id = names[event.title] ?: continue
            val ours = SwedishHolidays.forYear(event.start.year).single { it.id == id }
            assertEquals(event.start.date, ours.date, "${event.title} ${event.start.year}")
            checked++
        }
        assertTrue(checked >= 330, "checked $checked holidays")
    }
}

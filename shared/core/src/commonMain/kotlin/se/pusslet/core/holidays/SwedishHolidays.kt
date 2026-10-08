package se.pusslet.core.holidays

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import se.pusslet.core.time.isWeekend
import se.pusslet.core.time.minusDays
import se.pusslet.core.time.plusDays

/** How a special day affects family life. */
enum class HolidayKind {
    /** Allmän helgdag (röd dag enligt lag). */
    PublicHoliday,

    /** De facto ledig dag in Swedish working life: midsommarafton, julafton, nyårsafton. */
    DeFactoHoliday,

    /** Other eves that are normal working days (skärtorsdag, valborg …) or always weekends (påskafton). */
    Eve,

    /** Days worth knowing about: Mors dag, advent, sommartid … */
    Observance,
}

/** Identifiers only; display names live in string resources so they can be translated. */
enum class HolidayId(val kind: HolidayKind) {
    NewYearsDay(HolidayKind.PublicHoliday),
    TwelfthNight(HolidayKind.Eve),
    Epiphany(HolidayKind.PublicHoliday),
    ValentinesDay(HolidayKind.Observance),
    ShroveTuesday(HolidayKind.Observance),
    MaundyThursday(HolidayKind.Eve),
    GoodFriday(HolidayKind.PublicHoliday),
    EasterEve(HolidayKind.Eve),
    EasterSunday(HolidayKind.PublicHoliday),
    EasterMonday(HolidayKind.PublicHoliday),
    WalpurgisNight(HolidayKind.Eve),
    MayDay(HolidayKind.PublicHoliday),
    AscensionDay(HolidayKind.PublicHoliday),
    MothersDay(HolidayKind.Observance),
    WhitsunEve(HolidayKind.Eve),
    WhitSunday(HolidayKind.PublicHoliday),
    NationalDay(HolidayKind.PublicHoliday),
    MidsummerEve(HolidayKind.DeFactoHoliday),
    MidsummerDay(HolidayKind.PublicHoliday),
    AllSaintsEve(HolidayKind.Eve),
    AllSaintsDay(HolidayKind.PublicHoliday),
    FathersDay(HolidayKind.Observance),
    FirstAdvent(HolidayKind.Observance),
    SecondAdvent(HolidayKind.Observance),
    ThirdAdvent(HolidayKind.Observance),
    FourthAdvent(HolidayKind.Observance),
    Lucia(HolidayKind.Observance),
    ChristmasEve(HolidayKind.DeFactoHoliday),
    ChristmasDay(HolidayKind.PublicHoliday),
    BoxingDay(HolidayKind.PublicHoliday),
    NewYearsEve(HolidayKind.DeFactoHoliday),
    DaylightSavingStart(HolidayKind.Observance),
    DaylightSavingEnd(HolidayKind.Observance),
}

data class Holiday(val date: LocalDate, val id: HolidayId) {
    val kind: HolidayKind get() = id.kind
}

/**
 * Swedish public holidays, eves and squeeze days ("klämdagar"), computed locally so they work offline
 * and can be unit tested. Rules from Lag (1989:253) om allmänna helgdagar.
 */
object SwedishHolidays {
    private val cache = HashMap<Int, List<Holiday>>()

    fun forYear(year: Int): List<Holiday> = cache.getOrPut(year) { compute(year) }

    fun forDate(date: LocalDate): List<Holiday> = forYear(date.year).filter { it.date == date }

    fun inRange(start: LocalDate, endInclusive: LocalDate): List<Holiday> =
        (start.year..endInclusive.year).flatMap { forYear(it) }.filter { it.date in start..endInclusive }

    /** Red day in the calendar: every Sunday and every public holiday. */
    fun isRedDay(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SUNDAY || forDate(date).any { it.kind == HolidayKind.PublicHoliday }

    /** A day most adults are off work: weekends, public holidays and de facto holidays. */
    fun isNonWorkingDay(date: LocalDate): Boolean =
        date.isWeekend || forDate(date).any { it.kind == HolidayKind.PublicHoliday || it.kind == HolidayKind.DeFactoHoliday }

    /** Klämdag: a working day squeezed between two non-working days (e.g. Friday after Kristi himmelsfärd). */
    fun isSqueezeDay(date: LocalDate): Boolean =
        !isNonWorkingDay(date) && isNonWorkingDay(date.minusDays(1)) && isNonWorkingDay(date.plusDays(1))

    fun squeezeDays(year: Int): List<LocalDate> {
        val result = ArrayList<LocalDate>()
        var d = LocalDate(year, 1, 1)
        while (d.year == year) {
            if (isSqueezeDay(d)) result += d
            d = d.plusDays(1)
        }
        return result
    }

    /** Easter Sunday (Gregorian), anonymous algorithm (Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate(year, month, day)
    }

    private fun compute(year: Int): List<Holiday> {
        val easter = easterSunday(year)
        val midsummerDay = firstWeekdayOnOrAfter(LocalDate(year, Month.JUNE, 20), DayOfWeek.SATURDAY)
        val allSaintsDay = firstWeekdayOnOrAfter(LocalDate(year, Month.OCTOBER, 31), DayOfWeek.SATURDAY)
        // Fourth Sunday of Advent: the last Sunday on or before Christmas Eve (Dec 24 itself when it is a Sunday).
        val fourthAdvent = lastWeekdayOnOrBefore(LocalDate(year, Month.DECEMBER, 24), DayOfWeek.SUNDAY)
        val list = listOf(
            Holiday(LocalDate(year, 1, 1), HolidayId.NewYearsDay),
            Holiday(LocalDate(year, 1, 5), HolidayId.TwelfthNight),
            Holiday(LocalDate(year, 1, 6), HolidayId.Epiphany),
            Holiday(LocalDate(year, 2, 14), HolidayId.ValentinesDay),
            Holiday(easter.minusDays(47), HolidayId.ShroveTuesday),
            Holiday(easter.minusDays(3), HolidayId.MaundyThursday),
            Holiday(easter.minusDays(2), HolidayId.GoodFriday),
            Holiday(easter.minusDays(1), HolidayId.EasterEve),
            Holiday(easter, HolidayId.EasterSunday),
            Holiday(easter.plusDays(1), HolidayId.EasterMonday),
            Holiday(LocalDate(year, 4, 30), HolidayId.WalpurgisNight),
            Holiday(LocalDate(year, 5, 1), HolidayId.MayDay),
            Holiday(easter.plusDays(39), HolidayId.AscensionDay),
            Holiday(lastWeekdayOnOrBefore(LocalDate(year, 5, 31), DayOfWeek.SUNDAY), HolidayId.MothersDay),
            Holiday(easter.plusDays(48), HolidayId.WhitsunEve),
            Holiday(easter.plusDays(49), HolidayId.WhitSunday),
            Holiday(LocalDate(year, 6, 6), HolidayId.NationalDay),
            Holiday(midsummerDay.minusDays(1), HolidayId.MidsummerEve),
            Holiday(midsummerDay, HolidayId.MidsummerDay),
            Holiday(allSaintsDay.minusDays(1), HolidayId.AllSaintsEve),
            Holiday(allSaintsDay, HolidayId.AllSaintsDay),
            Holiday(firstWeekdayOnOrAfter(LocalDate(year, 11, 1), DayOfWeek.SUNDAY).plusDays(7), HolidayId.FathersDay),
            Holiday(fourthAdvent.minusDays(21), HolidayId.FirstAdvent),
            Holiday(fourthAdvent.minusDays(14), HolidayId.SecondAdvent),
            Holiday(fourthAdvent.minusDays(7), HolidayId.ThirdAdvent),
            Holiday(fourthAdvent, HolidayId.FourthAdvent),
            Holiday(LocalDate(year, 12, 13), HolidayId.Lucia),
            Holiday(LocalDate(year, 12, 24), HolidayId.ChristmasEve),
            Holiday(LocalDate(year, 12, 25), HolidayId.ChristmasDay),
            Holiday(LocalDate(year, 12, 26), HolidayId.BoxingDay),
            Holiday(LocalDate(year, 12, 31), HolidayId.NewYearsEve),
            // EU summer time: last Sunday of March and October.
            Holiday(lastWeekdayOnOrBefore(LocalDate(year, 3, 31), DayOfWeek.SUNDAY), HolidayId.DaylightSavingStart),
            Holiday(lastWeekdayOnOrBefore(LocalDate(year, 10, 31), DayOfWeek.SUNDAY), HolidayId.DaylightSavingEnd),
        )
        return list.sortedWith(compareBy({ it.date }, { it.id.ordinal }))
    }

    private fun lastWeekdayOnOrBefore(date: LocalDate, day: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.isoDayNumber - day.isoDayNumber + 7) % 7
        return date.minusDays(diff)
    }

    private fun firstWeekdayOnOrAfter(date: LocalDate, day: DayOfWeek): LocalDate {
        val diff = (day.isoDayNumber - date.dayOfWeek.isoDayNumber + 7) % 7
        return date.plusDays(diff)
    }
}

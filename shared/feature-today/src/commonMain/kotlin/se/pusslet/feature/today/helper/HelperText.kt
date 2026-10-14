package se.pusslet.feature.today.helper

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.helper.HelperAfter
import se.pusslet.core.helper.HelperMission
import se.pusslet.core.helper.HelperWeather
import se.pusslet.core.helper.HelperWeatherCondition
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.HandoffKind
import se.pusslet.core.time.plusDays
import se.pusslet.feature.today.SEPARATOR
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.format.weekdayName
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.helper_after
import se.pusslet.ui.resources.helper_bring
import se.pusslet.ui.resources.helper_bring_more
import se.pusslet.ui.resources.helper_weather_cold
import se.pusslet.ui.resources.helper_weather_dropoff
import se.pusslet.ui.resources.helper_weather_pickup
import se.pusslet.ui.resources.helper_weather_rain
import se.pusslet.ui.resources.helper_weather_snow
import se.pusslet.ui.resources.helper_week_number
import se.pusslet.ui.resources.helper_when_day
import se.pusslet.ui.resources.helper_when_today
import se.pusslet.ui.resources.helper_when_tomorrow
import se.pusslet.ui.resources.helper_you_both
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.today_handoff_stop
import se.pusslet.ui.resources.today_item_for
import se.pusslet.ui.resources.today_you_dropoff
import se.pusslet.ui.resources.today_you_pickup

/** Marks an argument inside a formatted string, so it can be set in bold whatever the language's word order. */
private const val ARG_START = '\u0001'
private const val ARG_END = '\u0002'
private val argMarker = Regex("$ARG_START(\\d+)$ARG_END")

/** [resource] formatted with [args] in SemiBold: "Du hämtar **Simon 15:30**". */
@Composable
internal fun emphasizedArgs(resource: StringResource, vararg args: String): AnnotatedString {
    val template = stringResource(resource, *args.indices.map { "$ARG_START$it$ARG_END" }.toTypedArray())
    return buildAnnotatedString {
        var from = 0
        argMarker.findAll(template).forEach { match ->
            append(template.substring(from, match.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(args[match.groupValues[1].toInt()]) }
            from = match.range.last + 1
        }
        append(template.substring(from))
    }
}

/** "Simon 15:30 och Ella 15:45"; children at the same time share it ("Simon och Ella 15:30"). */
@Composable
internal fun stopsText(missions: List<HelperMission>): String {
    val groups = ArrayList<Pair<LocalTime, MutableList<String>>>()
    missions.sortedBy { it.time }.forEach { m ->
        val names = m.children.map { it.name }
        val same = groups.lastOrNull()?.takeIf { it.first == m.time }
        if (same != null) same.second += names else groups += m.time to names.toMutableList()
    }
    return joinNames(groups.map { (time, names) -> stringResource(Res.string.today_handoff_stop, joinNames(names), formatTime(time)) })
}

/** "Du hämtar **Simon 15:30**". */
@Composable
internal fun missionText(m: HelperMission): AnnotatedString = emphasizedArgs(youResource(m.kind), stopsText(listOf(m)))

/** One day of missions: "Du hämtar Simon 15:30 och Ella 15:45", or "Du lämnar Simon 07:50 och hämtar Ella 15:45". */
@Composable
internal fun dayText(missions: List<HelperMission>): AnnotatedString {
    val dropOffs = missions.filter { it.kind == HandoffKind.DropOff }
    val pickUps = missions.filter { it.kind == HandoffKind.PickUp }
    return when {
        dropOffs.isEmpty() -> emphasizedArgs(Res.string.today_you_pickup, stopsText(pickUps))
        pickUps.isEmpty() -> emphasizedArgs(Res.string.today_you_dropoff, stopsText(dropOffs))
        else -> emphasizedArgs(Res.string.helper_you_both, stopsText(dropOffs), stopsText(pickUps))
    }
}

private fun youResource(kind: HandoffKind): StringResource =
    if (kind == HandoffKind.PickUp) Res.string.today_you_pickup else Res.string.today_you_dropoff

/** "Torsdag 15 oktober", with " · vecka 42" when the week changes. */
@Composable
internal fun dayTitle(date: LocalDate, week: Int?): String {
    val day = formatDateLong(date).replaceFirstChar { it.uppercase() }
    return if (week == null) day else day + SEPARATOR + stringResource(Res.string.helper_week_number, week)
}

/** "idag 16:30", "imorgon, fredag 16:30", "fredag 16:30" within the week, else "fredag 23 oktober 16:30". */
@Composable
internal fun whenText(date: LocalDate, time: LocalTime?, today: LocalDate): String {
    val days = date.toEpochDays() - today.toEpochDays()
    val dayName = if (days in 2..WEEK_AHEAD) weekdayName(date.dayOfWeek) else formatDateLong(date)
    val t = time?.let(::formatTime) ?: return dayName
    return when {
        date == today -> stringResource(Res.string.helper_when_today, t)
        date == today.plusDays(1) -> stringResource(Res.string.helper_when_tomorrow, weekdayName(date.dayOfWeek), t)
        else -> stringResource(Res.string.helper_when_day, dayName, t)
    }
}

private const val WEEK_AHEAD = 6

/** "Ta med: gympapåse, regnkläder och 2 till"; with more than one child each thing says whose it is. */
@Composable
internal fun bringText(m: HelperMission): String? {
    if (m.bring.isEmpty()) return null
    val separator = stringResource(Res.string.list_separator)
    val several = m.children.size > 1
    val things = m.bring.map { b ->
        val text = b.text.replaceFirstChar { it.lowercase() }
        if (several) stringResource(Res.string.today_item_for, text, b.child.name) else text
    }
    val list = if (m.moreToBring > 0) {
        stringResource(Res.string.helper_bring_more, things.joinToString(separator), m.moreToBring)
    } else {
        joinNames(things)
    }
    return stringResource(Res.string.helper_bring, list)
}

/** "Efteråt: Ella har Dans 17:15 · Kulturskolan". */
@Composable
internal fun afterText(after: HelperAfter): String {
    val text = stringResource(Res.string.helper_after, after.child.name, after.occurrence.event.title, formatTime(after.time))
    val place = after.occurrence.event.location.trim()
    return if (place.isEmpty()) text else text + SEPARATOR + place
}

/** "Regn vid hämtningen, 9°". */
@Composable
internal fun weatherText(w: HelperWeather): String {
    val condition = stringResource(
        when (w.condition) {
            HelperWeatherCondition.Rain -> Res.string.helper_weather_rain
            HelperWeatherCondition.Snow -> Res.string.helper_weather_snow
            HelperWeatherCondition.Cold -> Res.string.helper_weather_cold
        },
    )
    val resource = if (w.kind == HandoffKind.PickUp) Res.string.helper_weather_pickup else Res.string.helper_weather_dropoff
    return stringResource(resource, condition, w.temperature)
}

@Composable
internal fun names(people: List<FamilyPerson>): String = joinNames(people.map { it.name })

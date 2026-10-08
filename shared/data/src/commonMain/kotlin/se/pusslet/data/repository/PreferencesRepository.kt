package se.pusslet.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity

/** A typed device-local preference. */
class PrefKey<T>(val name: String, val serializer: KSerializer<T>, val default: T)

@Serializable
enum class CalendarLayer { Family, Subscriptions, Device, Holidays, Custody }

@Serializable
enum class CalendarView { Day, Week, Month, Agenda }

@Serializable
enum class AppTheme { System, Light, Dark }

@Serializable
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    /** Handles windows across midnight (22:00–07:00). */
    operator fun contains(t: LocalTime): Boolean = if (start <= end) t >= start && t < end else t >= start || t < end
}

@Serializable
data class MorningBrief(val enabled: Boolean = false, val time: LocalTime = LocalTime(6, 45))

object Prefs {
    val Theme = PrefKey("ui.theme", AppTheme.serializer(), AppTheme.System)
    val DynamicColor = PrefKey("ui.dynamicColor", Boolean.serializer(), false)
    val Language = PrefKey("ui.language", String.serializer(), "")
    val OnboardingDone = PrefKey("onboarding.done", Boolean.serializer(), false)
    val CalendarLayers = PrefKey("calendar.layers", SetSerializer(CalendarLayer.serializer()), CalendarLayer.entries.toSet())
    val CalendarViewMode = PrefKey("calendar.view", CalendarView.serializer(), CalendarView.Week)
    val CalendarPersonFilter = PrefKey("calendar.personFilter", SetSerializer(String.serializer()), emptySet())
    val DeviceCalendarsRead = PrefKey("calendar.device.read", SetSerializer(Long.serializer()), emptySet())
    val DeviceCalendarExport = PrefKey("calendar.device.export", Long.serializer(), -1L)
    val Morning = PrefKey("notify.morning", MorningBrief.serializer(), MorningBrief())
    val QuietHours = PrefKey("notify.quiet", TimeWindow.serializer(), TimeWindow(LocalTime(21, 0), LocalTime(6, 30)))
    val QuietHoursEnabled = PrefKey("notify.quiet.enabled", Boolean.serializer(), true)
    val CrashReportsConsent = PrefKey("privacy.crashReports", Boolean.serializer(), false)
    val ActiveShoppingList = PrefKey("shopping.activeList", String.serializer(), "")
    val ActiveStore = PrefKey("shopping.activeStore", String.serializer(), "")

    /** Staple suggestions dismissed with "Inte nu": key -> when (epoch ms). Device-local. */
    val DismissedStaples = PrefKey("shopping.dismissedStaples", MapSerializer(String.serializer(), Long.serializer()), emptyMap())
    val RecentQuickAdds = PrefKey("quickadd.recent", ListSerializer(String.serializer()), emptyList())
    val DeviceId = PrefKey("device.id", String.serializer(), "")
}

class PreferencesRepository(private val dao: PreferenceDao, private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun <T> observe(key: PrefKey<T>): Flow<T> = dao.observe(key.name).map { decode(key, it) }.distinctUntilChanged()

    suspend fun <T> get(key: PrefKey<T>): T = decode(key, dao.get(key.name))

    suspend fun <T> set(key: PrefKey<T>, value: T) = dao.put(PreferenceEntity(key.name, json.encodeToString(key.serializer, value)))

    suspend fun <T> update(key: PrefKey<T>, transform: (T) -> T) = set(key, transform(get(key)))

    suspend fun clearAll() = dao.clear()

    private fun <T> decode(key: PrefKey<T>, raw: String?): T =
        raw?.let { runCatching { json.decodeFromString(key.serializer, it) }.getOrNull() } ?: key.default
}

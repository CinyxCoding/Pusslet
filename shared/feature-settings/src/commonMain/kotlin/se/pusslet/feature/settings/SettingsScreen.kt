package se.pusslet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.calendar.DeviceExport
import se.pusslet.core.model.Place
import se.pusslet.core.model.Role
import se.pusslet.core.model.SwedishPlaces
import se.pusslet.data.AppCoordinator
import se.pusslet.data.BackendConfig
import se.pusslet.data.repository.AppTheme
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.DeviceCalendar
import se.pusslet.data.repository.DeviceCalendarSource
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.MorningBrief
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.TimeWindow
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.SyncLocalStore
import se.pusslet.data.usecase.CalendarFeedLink
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.NavRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.Section
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.components.TimeField
import se.pusslet.ui.components.ValueField
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.platform.AppPermission
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

data class SettingsUiState(
    val theme: AppTheme = AppTheme.System,
    val dynamicColor: Boolean = false,
    val place: Place? = null,
    val morning: MorningBrief = MorningBrief(),
    val quietEnabled: Boolean = true,
    val quiet: TimeWindow = TimeWindow(LocalTime(21, 0), LocalTime(6, 30)),
    val readCalendars: Set<Long> = emptySet(),
    val exportCalendar: Long = -1,
    val crashReports: Boolean = false,
    val version: String = "",
    /** Weather place and calendar export write what the family shares; caregivers cannot (firestore.rules). */
    val canEditFamily: Boolean = true,
    /** Demo and dev builds: the developer rows ("Visa första start igen"). */
    val developerTools: Boolean = false,
    val replayFailed: Boolean = false,
)

class SettingsViewModel(
    private val prefs: PreferencesRepository,
    private val family: FamilyRepository,
    private val deviceCalendars: DeviceCalendarSource,
    private val calendar: CalendarRepository,
    config: BackendConfig,
    sessions: SessionProvider,
    private val coordinator: AppCoordinator,
    private val local: SyncLocalStore,
    private val feeds: CalendarFeedLink,
) : ViewModel() {
    val calendars = MutableStateFlow<List<DeviceCalendar>>(emptyList())
    private val replayFailed = MutableStateFlow(false)

    /** The family's own calendar feed made on this phone (ADR-041); null when there is none. */
    val feedLink: StateFlow<String?> = feeds.link.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val feedAvailable: Boolean get() = feeds.available
    val feedFailed = MutableStateFlow(false)

    private val appearance =
        combine(prefs.observe(Prefs.Theme), prefs.observe(Prefs.DynamicColor), family.settings) { t, d, s -> Triple(t, d, s.weatherPlace) }
    private val notify =
        combine(prefs.observe(Prefs.Morning), prefs.observe(Prefs.QuietHoursEnabled), prefs.observe(Prefs.QuietHours)) { m, qe, q ->
            Triple(m, qe, q)
        }
    private val cal =
        combine(
            prefs.observe(Prefs.DeviceCalendarsRead),
            prefs.observe(Prefs.DeviceCalendarExport),
            prefs.observe(Prefs.CrashReportsConsent),
        ) {
            r,
            e,
            c,
            ->
            Triple(r, e, c)
        }

    private val access = combine(sessions.session, replayFailed) { s, failed -> (s.role == Role.Parent) to failed }

    val state: StateFlow<SettingsUiState> = combine(appearance, notify, cal, access) { a, n, c, x ->
        SettingsUiState(
            a.first,
            a.second,
            a.third,
            n.first,
            n.second,
            n.third,
            c.first,
            c.second,
            c.third,
            config.versionName,
            canEditFamily = x.first,
            developerTools = config.developerTools,
            replayFailed = x.second,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(
            version = config.versionName,
            canEditFamily = sessions.session.value.role == Role.Parent,
            developerTools = config.developerTools,
        ),
    )

    fun loadCalendars() {
        viewModelScope.launch { calendars.value = deviceCalendars.calendars() }
    }

    fun setTheme(t: AppTheme) = launch { prefs.set(Prefs.Theme, t) }

    fun setDynamic(v: Boolean) = launch { prefs.set(Prefs.DynamicColor, v) }

    fun setPlace(p: Place) = launch { family.saveSettings { it.copy(weatherPlace = p) } }

    fun setMorning(m: MorningBrief) = launch { prefs.set(Prefs.Morning, m) }

    fun setQuietEnabled(v: Boolean) = launch { prefs.set(Prefs.QuietHoursEnabled, v) }

    fun setQuiet(w: TimeWindow) = launch { prefs.set(Prefs.QuietHours, w) }

    fun toggleReadCalendar(id: Long) = launch { prefs.update(Prefs.DeviceCalendarsRead) { if (id in it) it - id else it + id } }

    fun setCrashReports(v: Boolean) = launch { prefs.set(Prefs.CrashReportsConsent, v) }

    /**
     * Picks (or creates) the calendar family events are written to and exports right away; the copies in the calendar
     * chosen before are taken away. From then on [se.pusslet.data.sources.DeviceCalendarExporter] keeps it current.
     */
    fun setExport(id: Long?, appCalendarName: String) = launch {
        val target = when (id) {
            null -> -1L
            APP_CALENDAR -> deviceCalendars.ensureAppCalendar(appCalendarName) ?: -1L
            else -> id
        }
        val previous = prefs.get(Prefs.DeviceCalendarExport)
        if (previous >= 0 && previous != target) deviceCalendars.removeExported(previous, emptySet())
        prefs.set(Prefs.DeviceCalendarExport, target)
        if (target >= 0) {
            val events = DeviceExport.prepare(calendar.events.first())
            deviceCalendars.exportEvents(target, events)
            deviceCalendars.removeExported(target, events.mapTo(HashSet()) { it.id })
        }
        calendars.value = deviceCalendars.calendars()
    }

    /** "Skapa länk" for the family's calendar in other apps; [feedFailed] when it could not be saved (no network). */
    fun createFeed() = launch { feedFailed.value = !feeds.create() }

    /** "Sluta dela": every calendar that subscribed loses the family's events at its next update. */
    fun revokeFeed() = launch { feedFailed.value = !feeds.revoke() }

    /** Developer tools: forgets the family on this device and resets onboarding; the shell then shows the welcome screen. */
    fun replayOnboarding() = launch { replayFailed.value = !coordinator.replayOnboarding(local) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        const val APP_CALENDAR = -2L
    }
}

/** "Familjens kalender i andra appar": make, share or stop the secret feed link (ADR-041). */
@Composable
private fun CalendarFeedRows(viewModel: SettingsViewModel) {
    val link by viewModel.feedLink.collectAsStateWithLifecycle()
    val failed by viewModel.feedFailed.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val subject = stringResource(Res.string.settings_feed_title)
    Text(subject, style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(Res.string.settings_feed_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val current = link
    if (current == null) {
        OutlinedButton(onClick = viewModel::createFeed) { Text(stringResource(Res.string.settings_feed_create)) }
    } else {
        SelectionContainer { Text(current, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(onClick = { platform.shareText(current, subject) }) { Text(stringResource(Res.string.settings_feed_share)) }
            TextButton(onClick = viewModel::revokeFeed) { Text(stringResource(Res.string.settings_feed_stop)) }
        }
    }
    if (failed) Banner(stringResource(Res.string.settings_feed_failed), kind = BannerKind.Warning)
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onCustomizeToday: () -> Unit = {},
    /** "Prenumerationer" (ICS feeds); the row is shown to parents only, and only when the shell passes this. */
    onSubscriptions: (() -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    var pickPlace by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(platform.currentLanguage()) }
    val appCalendarName = stringResource(Res.string.app_name)
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.settings_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            // Flat groups with room between them instead of cards (UX plan Steg 2, Mer 3).
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Section(stringResource(Res.string.settings_appearance)) {
                Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(
                        AppTheme.System to Res.string.theme_system,
                        AppTheme.Light to Res.string.theme_light,
                        AppTheme.Dark to Res.string.theme_dark,
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = state.theme == t,
                            onClick = { viewModel.setTheme(t) },
                            label = { Text(stringResource(label)) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                }
                SwitchRow(stringResource(Res.string.settings_dynamic_color), state.dynamicColor, viewModel::setDynamic)
                NavRow(stringResource(Res.string.today_customize), onClick = onCustomizeToday)
                Text(stringResource(Res.string.settings_language), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(
                        "" to Res.string.language_system,
                        "sv" to Res.string.language_sv,
                        "en" to Res.string.language_en,
                    ).forEach { (tag, label) ->
                        FilterChip(
                            selected = language == tag,
                            onClick = {
                                language = tag
                                platform.setLanguage(tag)
                            },
                            label = { Text(stringResource(label)) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                }
            }
            if (state.canEditFamily) {
                Section(stringResource(Res.string.settings_weather_place)) {
                    ValueField(
                        stringResource(Res.string.settings_weather_place),
                        state.place?.name?.ifBlank { null } ?: stringResource(Res.string.settings_weather_place_none),
                        onClick = { pickPlace = true },
                    )
                }
            }
            Section(stringResource(Res.string.settings_notifications)) {
                SwitchRow(
                    stringResource(Res.string.settings_morning),
                    state.morning.enabled,
                    { on ->
                        if (on && !platform.hasPermission(AppPermission.Notifications)) {
                            platform.requestPermission(AppPermission.Notifications) { granted ->
                                if (granted) viewModel.setMorning(state.morning.copy(enabled = true))
                            }
                        } else {
                            viewModel.setMorning(state.morning.copy(enabled = on))
                        }
                    },
                    supporting = stringResource(Res.string.settings_morning_body, formatTime(state.morning.time)),
                )
                if (state.morning.enabled) {
                    TimeField(stringResource(Res.string.field_time), state.morning.time, {
                        viewModel.setMorning(state.morning.copy(time = it))
                    })
                }
                SwitchRow(
                    stringResource(Res.string.settings_quiet_hours),
                    state.quietEnabled,
                    viewModel::setQuietEnabled,
                    supporting = stringResource(
                        Res.string.settings_quiet_hours_body,
                        formatTime(state.quiet.start),
                        formatTime(state.quiet.end),
                    ),
                )
                if (state.quietEnabled) {
                    TimeField(stringResource(Res.string.field_start), state.quiet.start, {
                        viewModel.setQuiet(state.quiet.copy(start = it))
                    })
                    TimeField(stringResource(Res.string.field_end), state.quiet.end, { viewModel.setQuiet(state.quiet.copy(end = it)) })
                }
            }
            Section(stringResource(Res.string.settings_calendars)) {
                if (onSubscriptions != null && state.canEditFamily) NavRow(stringResource(Res.string.subs_title), onClick = onSubscriptions)
                val hasPermission = platform.hasPermission(AppPermission.Calendar)
                SwitchRow(
                    stringResource(Res.string.settings_device_calendars),
                    hasPermission && calendars.isNotEmpty(),
                    { on ->
                        if (on) platform.requestPermission(AppPermission.Calendar) { granted -> if (granted) viewModel.loadCalendars() }
                    },
                    supporting = stringResource(Res.string.settings_device_calendars_body),
                )
                androidx.compose.runtime.LaunchedEffect(hasPermission) { if (hasPermission) viewModel.loadCalendars() }
                calendars.filter { !it.ownedByApp }.forEach { c ->
                    SwitchRow(c.name, c.id in state.readCalendars, { viewModel.toggleReadCalendar(c.id) }, supporting = c.accountName)
                }
                if (hasPermission && state.canEditFamily) {
                    Text(stringResource(Res.string.settings_export_calendar), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        FilterChip(
                            selected = state.exportCalendar < 0,
                            onClick = { viewModel.setExport(null, appCalendarName) },
                            label = { Text(stringResource(Res.string.settings_export_none)) },
                            colors = SelectionColors.filterChip(),
                        )
                        val app = calendars.firstOrNull { it.ownedByApp }
                        FilterChip(
                            selected = app != null && state.exportCalendar == app.id,
                            onClick = { viewModel.setExport(app?.id ?: SettingsViewModel.APP_CALENDAR, appCalendarName) },
                            label = { Text(stringResource(Res.string.settings_export_app_calendar)) },
                            colors = SelectionColors.filterChip(),
                        )
                        calendars.filter { it.writable && !it.ownedByApp }.forEach { c ->
                            FilterChip(
                                selected = state.exportCalendar == c.id,
                                onClick = { viewModel.setExport(c.id, appCalendarName) },
                                label = { Text(c.name) },
                                colors = SelectionColors.filterChip(),
                            )
                        }
                    }
                }
                if (state.canEditFamily && viewModel.feedAvailable) CalendarFeedRows(viewModel)
            }
            // The crash-report switch comes back with crash reporting itself (Crashlytics is not in the build yet, PRIVACY.md).
            if (state.developerTools) {
                Section(stringResource(Res.string.settings_dev_title)) {
                    OutlinedButton(
                        onClick = viewModel::replayOnboarding,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                    ) { Text(stringResource(Res.string.settings_dev_replay_onboarding)) }
                    if (state.replayFailed) {
                        Text(
                            stringResource(Res.string.error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                stringResource(Res.string.settings_version, state.version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (pickPlace) PlacePickerDialog(onDismiss = { pickPlace = false }, onPick = { viewModel.setPlace(it) })
}

@Composable
fun PlacePickerDialog(onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        title = { Text(stringResource(Res.string.settings_weather_place)) },
        text = {
            Column {
                OutlinedTextField(query, {
                    query = it
                }, label = {
                    Text(stringResource(Res.string.onboarding_place_search))
                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(SwedishPlaces.search(query), key = { it.name }) { p ->
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                onPick(p)
                                onDismiss()
                            }.padding(vertical = Spacing.md),
                        )
                    }
                }
            }
        },
    )
}

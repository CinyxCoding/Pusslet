package se.pusslet.android.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import se.pusslet.core.time.plusDays
import se.pusslet.data.widget.WidgetSource
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the home-screen widgets current (ADR-039). Glance only recomposes a widget whose session is still running, so
 * [refresh] first bumps [VERSION] in each widget's state and the widget reloads its data on it. Asked for: a moment after
 * any change in the data while the app runs ([start]), after a tick or an addition from a widget, and for Idag when time
 * moves it on ([scheduleToday]: the next start, end or hand-off, else just after midnight).
 */
object WidgetUpdates {
    internal val VERSION = longPreferencesKey("pusslet.version")

    private val log = Logger.withTag("Widgets")
    private const val TODAY_REFRESH = "pusslet-widget-today"
    private val CHANGE_DEBOUNCE: Duration = 1.seconds

    /** A hand-off or a start "has passed" a moment after its minute; a little later is safe, earlier is not. */
    private val MARGIN: Duration = 30.seconds
    private val AFTER_MIDNIGHT = LocalTime(0, 1)

    /** From `Application.onCreate`: draws the widgets again a moment after anything they show changes. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, source: WidgetSource, scope: CoroutineScope) {
        scope.launch {
            source.changes()
                .debounce(CHANGE_DEBOUNCE)
                .catch { e -> log.w(e) { "Widget updates stopped" } }
                .collectLatest {
                    // The app scope has no handler: a failed run is logged and the next change tries again.
                    try {
                        refreshAll(context)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        log.w(e) { "Could not update the widgets" }
                    }
                }
        }
    }

    suspend fun refreshAll(context: Context) {
        refresh(context, TodayWidget())
        refresh(context, ShoppingWidget())
    }

    /** Draws every placed [widget] again with fresh data; nothing when none is on the home screen. */
    suspend fun refresh(context: Context, widget: GlanceAppWidget) {
        GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).forEach { id ->
            updateAppWidgetState(context, id) { state -> state[VERSION] = (state[VERSION] ?: 0L) + 1 }
            widget.update(context, id)
        }
    }

    /** Draws Idag again when [at] (today) has passed, or just after midnight when nothing changes today. */
    fun scheduleToday(context: Context, at: LocalTime?, now: LocalDateTime, zone: TimeZone) {
        val next = at?.let { LocalDateTime(now.date, it) } ?: LocalDateTime(now.date.plusDays(1), AFTER_MIDNIGHT)
        val delay = (next.toInstant(zone) - now.toInstant(zone) + MARGIN).coerceAtLeast(Duration.ZERO)
        WorkManager.getInstance(context).enqueueUniqueWork(
            TODAY_REFRESH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshTodayWidgetWorker>().setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS).build(),
        )
    }

    /** The last Idag widget was removed. */
    fun cancelToday(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TODAY_REFRESH)
    }
}

/** Idag's next moment of change ([WidgetUpdates.scheduleToday]). */
class RefreshTodayWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdates.refresh(applicationContext, TodayWidget())
        return Result.success()
    }
}

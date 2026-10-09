package se.pusslet.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import se.pusslet.android.MainActivity
import se.pusslet.android.notify.Notifications
import se.pusslet.core.model.MemberColor
import se.pusslet.data.repository.CalendarRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PackingRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.shell.navigation.TopLevelTab
import se.pusslet.ui.theme.PussletColorSchemes
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The app's sage/oat palette, light and dark – not wallpaper colours, like the app itself. */
internal val PussletGlanceColors = ColorProviders(light = PussletColorSchemes.light, dark = PussletColorSchemes.dark)

internal fun memberColor(c: MemberColor): ColorProvider = ColorProvider(day = Color(c.lightContainer), night = Color(c.darkContainer))

object WidgetIntents {
    const val EXTRA_TAB = "se.pusslet.extra.TAB"

    fun openTab(context: Context, tab: TopLevelTab): Intent = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_TAB, tab.name)

    fun openEvent(context: Context, eventId: String, date: LocalDate): Intent = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(Notifications.EXTRA_EVENT_ID, eventId)
        .putExtra(Notifications.EXTRA_DATE, date.toString())

    fun quickAdd(context: Context, listId: String): Intent = Intent(context, ShoppingQuickAddActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        .putExtra(ShoppingQuickAddActivity.EXTRA_LIST_ID, listId)
}

/** Keeps both widgets in step with the family's data while the app process is alive, and the Today widget with the clock. */
class WidgetUpdates(
    private val context: Context,
    private val calendar: CalendarRepository,
    private val packing: PackingRepository,
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    private val sessions: SessionProvider,
) {
    private val log = Logger.withTag("Widgets")

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(calendar.events, packing.rules, packing.checks, family.people, sessions.session) { _, _, _, _, _ -> }
                .drop(1)
                .debounce(DEBOUNCE)
                .collect { update { TodayWidget().updateAll(context) } }
        }
        scope.launch {
            combine(shopping.lists, shopping.items, shopping.stores, sessions.session) { _, _, _, _ -> }
                .drop(1)
                .debounce(DEBOUNCE)
                .collect { update { ShoppingWidget().updateAll(context) } }
        }
    }

    private suspend fun update(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "Widget update failed: ${e::class.simpleName}" }
        }
    }

    private companion object {
        val DEBOUNCE = 1.seconds
    }
}

/** Wakes the Today widget when its content changes by itself (an event starts or ends, a pick-up passes, midnight). */
class WidgetRefresh(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TodayWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "widget-today-refresh"

        fun scheduleToday(context: Context, at: Instant, clock: Clock) {
            val delay = (at - clock.now()).inWholeMilliseconds.coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<WidgetRefresh>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

package se.pusslet.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.android.platform.useAppLanguage
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.core.widget.GlanceEvent
import se.pusslet.data.widget.WidgetSource
import se.pusslet.ui.widget.TodayWidgetText
import se.pusslet.ui.widget.WidgetTexts
import kotlin.time.Clock

/**
 * The Idag widget (BRIEF §4.11, ADR-039): the date, the next event, who drops off and picks up, and what to bring, worded
 * as Morgonkollen. A tap opens Idag; the event's line opens the event. Drawn again on changes and when time moves it on
 * ([WidgetUpdates]).
 */
class TodayWidget :
    GlanceAppWidget(),
    KoinComponent {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val loadedFor = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WidgetUpdates.VERSION] ?: 0L
        val first = load(context)
        provideContent {
            val version = currentState(WidgetUpdates.VERSION) ?: 0L
            val content by produceState(first, version) { if (version != loadedFor) value = load(context) }
            PussletGlanceTheme { TodayBody(context, content) }
        }
    }

    private suspend fun load(context: Context): TodayContent {
        useAppLanguage(context)
        val now = get<Clock>().now().toLocalDateTime(get<TimeZone>())
        val glance = get<WidgetSource>().today()
        WidgetUpdates.scheduleToday(context, glance?.nextChange, now, get())
        return TodayContent(WidgetTexts().today(glance, now.date), glance?.next)
    }
}

private data class TodayContent(val text: TodayWidgetText, val next: GlanceEvent?)

@Composable
private fun TodayBody(context: Context, content: TodayContent) {
    val text = content.text
    Column(GlanceModifier.fillMaxSize().widgetCard().padding(16.dp).clickable(openApp(context, NotificationTarget.Today))) {
        Text(text.date, style = WidgetText.heading(), maxLines = 1)
        text.problems?.let { Text(it, style = WidgetText.line(GlanceTheme.colors.error), modifier = GlanceModifier.padding(top = 4.dp)) }
        text.next?.let { next ->
            val event = content.next
            val row = GlanceModifier.fillMaxWidth().padding(top = 8.dp)
            Row(if (event == null) row else row.clickable(openApp(context, NotificationTarget.Occurrence(event.eventId, event.date)))) {
                Text(next, style = WidgetText.line(), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                text.later?.let { Text(it, style = WidgetText.detail(), maxLines = 1, modifier = GlanceModifier.padding(start = 8.dp)) }
            }
        }
        text.handoffs.forEach { Text(it, style = WidgetText.line(), maxLines = 2, modifier = GlanceModifier.padding(top = 4.dp)) }
        text.packing?.let { Text(it, style = WidgetText.detail(), maxLines = 2, modifier = GlanceModifier.padding(top = 4.dp)) }
        text.empty?.let { Text(it, style = WidgetText.detail(), modifier = GlanceModifier.padding(top = 8.dp)) }
    }
}

/** Placed on the home screen; Glance draws it. */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdates.cancelToday(context)
    }
}

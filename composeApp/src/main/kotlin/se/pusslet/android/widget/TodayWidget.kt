package se.pusslet.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.onEach
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.shell.navigation.TopLevelTab
import kotlin.time.Clock

/** "Idag": the next thing, who drops off and picks up, and what has to come along. */
class TodayWidget :
    GlanceAppWidget(),
    KoinComponent {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val content = get<TodayWidgetContent>()
        val clock = get<Clock>()
        val first = content.load()
        first.refreshAt?.let { WidgetRefresh.scheduleToday(context, it, clock) }
        provideContent {
            val updates = remember {
                content.updates().onEach { s -> s.refreshAt?.let { WidgetRefresh.scheduleToday(context, it, clock) } }
            }
            val state by updates.collectAsState(first)
            GlanceTheme(colors = PussletGlanceColors) { TodayContent(state.ui) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
fun TodayContent(ui: TodayWidgetUi) {
    val context = LocalContext.current
    Column(
        modifier = widgetRoot().clickable(actionStartActivity(WidgetIntents.openTab(context, TopLevelTab.Today))),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui.title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = if (ui.setUp) 1 else 3,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (ui.week.isNotEmpty()) {
                Text(text = ui.week, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
            }
        }
        ui.message?.let {
            Spacer(GlanceModifier.height(6.dp))
            Text(text = it, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
        }
        if (ui.lines.isNotEmpty()) {
            Spacer(GlanceModifier.height(6.dp))
            LazyColumn {
                items(ui.lines) { line -> TodayLine(line) }
            }
        }
    }
}

@Composable
private fun TodayLine(line: WidgetLine) {
    val context = LocalContext.current
    val open = if (line.eventId != null && line.date != null) {
        GlanceModifier.clickable(actionStartActivity(WidgetIntents.openEvent(context, line.eventId, line.date)))
    } else {
        GlanceModifier
    }
    val accent = if (line.warning) GlanceTheme.colors.error else GlanceTheme.colors.primary
    Row(modifier = open.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = line.label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent),
            maxLines = 1,
            modifier = GlanceModifier.width(LABEL_WIDTH.dp),
        )
        line.colors.distinct().take(MAX_DOTS).forEach { color ->
            Box(modifier = GlanceModifier.size(DOT.dp).cornerRadius((DOT / 2).dp).background(memberColor(color))) {}
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(
            text = line.text,
            style = TextStyle(fontSize = 13.sp, color = if (line.warning) GlanceTheme.colors.error else GlanceTheme.colors.onSurface),
            maxLines = 2,
        )
    }
}

/** The rounded card every Pusslet widget sits on. */
@Composable
internal fun widgetRoot(): GlanceModifier = GlanceModifier
    .fillMaxSize()
    .cornerRadius(CORNER.dp)
    .background(GlanceTheme.colors.surface)
    .padding(PADDING.dp)

private const val LABEL_WIDTH = 66
private const val MAX_DOTS = 3
private const val DOT = 8
private const val CORNER = 20
private const val PADDING = 12

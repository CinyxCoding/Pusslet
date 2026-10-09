package se.pusslet.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.android.R
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.shell.navigation.TopLevelTab

/** "Inköpslista": the list in walking order, tick things off, + to add. */
class ShoppingWidget :
    GlanceAppWidget(),
    KoinComponent {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = get<ShoppingWidgetContent>().load()
        provideContent {
            GlanceTheme(colors = PussletGlanceColors) { ShoppingContent(ui) }
        }
    }
}

class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingWidget()
}

/** Ticking an item off from the home screen; the list in the app updates the same way. */
class CheckOffAction :
    ActionCallback,
    KoinComponent {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ITEM_ID] ?: return
        val shopping = get<ShoppingRepository>()
        val item = shopping.items.first().firstOrNull { it.id == id } ?: return
        shopping.setChecked(item, true)
        ShoppingWidget().update(context, glanceId)
    }

    companion object {
        val ITEM_ID = ActionParameters.Key<String>("itemId")
    }
}

@Composable
fun ShoppingContent(ui: ShoppingWidgetUi) {
    val context = LocalContext.current
    val openList = actionStartActivity(WidgetIntents.openTab(context, TopLevelTab.Shopping))
    Column(modifier = widgetRoot()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui.title,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = if (ui.setUp) 1 else 3,
                modifier = GlanceModifier.defaultWeight().clickable(openList),
            )
            ui.listId?.let { listId ->
                Box(
                    modifier = GlanceModifier
                        .size(ADD_SIZE.dp)
                        .cornerRadius((ADD_SIZE / 2).dp)
                        .background(GlanceTheme.colors.primary)
                        .clickable(actionStartActivity(WidgetIntents.quickAdd(context, listId))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = ui.addLabel,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                        modifier = GlanceModifier.size(ICON_SIZE.dp),
                    )
                }
            }
        }
        if (!ui.setUp) return@Column
        Spacer(GlanceModifier.height(4.dp))
        if (ui.items.isEmpty()) {
            Text(
                text = ui.empty,
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.clickable(openList),
            )
        } else {
            LazyColumn {
                items(ui.items, itemId = { it.id.hashCode().toLong() }) { item ->
                    CheckBox(
                        checked = false,
                        onCheckedChange = actionRunCallback<CheckOffAction>(actionParametersOf(CheckOffAction.ITEM_ID to item.id)),
                        text = item.text,
                        style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
                        maxLines = 2,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private const val ADD_SIZE = 36
private const val ICON_SIZE = 20

package se.pusslet.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import se.pusslet.android.R
import se.pusslet.android.platform.useAppLanguage
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.core.widget.ShoppingGlance
import se.pusslet.data.widget.WidgetSource
import se.pusslet.ui.widget.ShoppingWidgetText
import se.pusslet.ui.widget.WidgetTexts

/**
 * The Inköpslistan widget (BRIEF §4.11, ADR-039): what is left on the list Handla shows, in the store's order. A tick marks
 * it bought, + opens a field over the home screen ([ShoppingQuickAddActivity]), and the heading opens Handla.
 */
class ShoppingWidget :
    GlanceAppWidget(),
    KoinComponent {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val loadedFor = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WidgetUpdates.VERSION] ?: 0L
        val first = load(context)
        provideContent {
            val version = currentState(WidgetUpdates.VERSION) ?: 0L
            val content by produceState(first, version) { if (version != loadedFor) value = load(context) }
            PussletGlanceTheme { ShoppingBody(context, content) }
        }
    }

    private suspend fun load(context: Context): ShoppingContent {
        useAppLanguage(context)
        val glance = get<WidgetSource>().shopping()
        return ShoppingContent(WidgetTexts().shopping(glance), glance)
    }
}

private data class ShoppingContent(val text: ShoppingWidgetText, val glance: ShoppingGlance?)

@Composable
private fun ShoppingBody(context: Context, content: ShoppingContent) {
    val text = content.text
    val glance = content.glance
    // Without a family there is no list yet: + opens the app, which shows how to get started.
    val add = if (glance == null) openApp(context, NotificationTarget.Today) else actionStartActivity<ShoppingQuickAddActivity>()
    Column(GlanceModifier.fillMaxSize().widgetCard().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(openApp(context, NotificationTarget.Shopping))) {
                Text(text.title, style = WidgetText.heading(), maxLines = 1)
                text.left?.let { Text(it, style = WidgetText.detail(), maxLines = 1) }
            }
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = text.add,
                onClick = add,
                backgroundColor = GlanceTheme.colors.primary,
                contentColor = GlanceTheme.colors.onPrimary,
            )
        }
        val items = glance?.items.orEmpty()
        val empty = text.empty
        if (empty != null) {
            Text(empty, style = WidgetText.detail(), modifier = GlanceModifier.padding(top = 8.dp))
        } else {
            LazyColumn(GlanceModifier.fillMaxSize().padding(top = 4.dp)) {
                items(items, itemId = { it.id.hashCode().toLong() }) { item ->
                    CheckBox(
                        checked = false,
                        onCheckedChange = actionRunCallback<TickOffAction>(actionParametersOf(TickOffAction.ITEM to item.id)),
                        text = item.text,
                        style = WidgetText.line(),
                        maxLines = 2,
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** A tick in the widget: bought, as a tick in Handla. */
class TickOffAction :
    ActionCallback,
    KoinComponent {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[ITEM] ?: return
        get<WidgetSource>().setBought(itemId)
        WidgetUpdates.refresh(context, ShoppingWidget())
    }

    companion object {
        val ITEM = ActionParameters.Key<String>("item")
    }
}

/** Placed on the home screen; Glance draws it. */
class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingWidget()
}

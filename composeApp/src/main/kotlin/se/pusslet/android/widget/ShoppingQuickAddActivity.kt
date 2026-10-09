package se.pusslet.android.widget

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.android.ext.android.inject
import se.pusslet.data.repository.AppTheme
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.usecase.ShoppingQuickAdd
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_cancel
import se.pusslet.ui.resources.shopping_add
import se.pusslet.ui.resources.shopping_add_hint
import se.pusslet.ui.resources.widget_shopping_add_title
import se.pusslet.ui.theme.PussletTheme
import se.pusslet.ui.theme.ThemeMode

/** The widget's +: a small dialog over the home screen ("mjölk, 2 gurka"), with the same parsing as the Handla tab. */
class ShoppingQuickAddActivity : AppCompatActivity() {
    private val quickAdd: ShoppingQuickAdd by inject()
    private val shopping: ShoppingRepository by inject()
    private val prefs: PreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listId = intent.getStringExtra(EXTRA_LIST_ID)
        if (listId == null) {
            finish()
            return
        }
        setContent {
            var listName by remember { mutableStateOf("") }
            var theme by remember { mutableStateOf(ThemeMode.System) }
            LaunchedEffect(listId) {
                listName = shopping.lists.first().firstOrNull { it.id == listId }?.name.orEmpty()
                theme = when (prefs.get(Prefs.Theme)) {
                    AppTheme.System -> ThemeMode.System
                    AppTheme.Light -> ThemeMode.Light
                    AppTheme.Dark -> ThemeMode.Dark
                }
            }
            PussletTheme(themeMode = theme) {
                QuickAddDialog(listName = listName, onDismiss = ::finish, onAdd = { text -> add(listId, text) })
            }
        }
    }

    private fun add(listId: String, text: String) {
        lifecycleScope.launch {
            quickAdd.add(listId, text)
            ShoppingWidget().updateAll(applicationContext)
            finish()
        }
    }

    companion object {
        const val EXTRA_LIST_ID = "se.pusslet.extra.LIST_ID"
    }
}

@Composable
private fun QuickAddDialog(listName: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = { if (text.isNotBlank()) onAdd(text.trim()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.widget_shopping_add_title, listName), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(Res.string.shopping_add_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    Button(onClick = { submit() }, enabled = text.isNotBlank()) { Text(stringResource(Res.string.shopping_add)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

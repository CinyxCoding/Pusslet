package se.pusslet.android.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.android.ext.android.get
import se.pusslet.android.platform.useAppLanguage
import se.pusslet.data.usecase.ShoppingAdder
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_add
import se.pusslet.ui.resources.action_cancel
import se.pusslet.ui.resources.error_generic
import se.pusslet.ui.resources.list_separator
import se.pusslet.ui.resources.shopping_add_action
import se.pusslet.ui.resources.shopping_added
import se.pusslet.ui.resources.shopping_empty_body
import se.pusslet.ui.theme.PussletTheme

/**
 * + on the Inköpslistan widget (BRIEF §4.11 "snabbt lägga till", ADR-039): one field over the home screen, read like the
 * list's own ("2 l mjölk, bröd"), onto the list Handla shows. Closes when done; the widget is drawn again.
 */
class ShoppingQuickAddActivity : AppCompatActivity() {
    private val log = Logger.withTag("WidgetAdd")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PussletTheme {
                QuickAddDialog(onDismiss = ::finish, onAdd = ::add)
            }
        }
    }

    private fun add(text: String) {
        lifecycleScope.launch {
            useAppLanguage(applicationContext)
            val message = try {
                val adder = get<ShoppingAdder>()
                val outcome = adder.addText(adder.activeListId(), text)
                WidgetUpdates.refresh(applicationContext, ShoppingWidget())
                val names = outcome.names.joinToString(getString(Res.string.list_separator))
                if (names.isBlank()) null else getString(Res.string.shopping_added, names)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.w(e) { "Could not add from the widget" }
                getString(Res.string.error_generic)
            }
            message?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
            finish()
        }
    }
}

@Composable
private fun QuickAddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val send = {
        if (text.isNotBlank() && !sent) {
            sent = true
            onAdd(text)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shopping_add_action)) },
        text = {
            // The dialog has a window of its own, so the keyboard is asked for from inside it.
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(Res.string.shopping_empty_body)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = send, enabled = text.isNotBlank() && !sent) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

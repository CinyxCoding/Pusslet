package se.pusslet.feature.today.helper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.LoadingBox
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.empty_generic
import se.pusslet.ui.resources.helper_preview_hidden
import se.pusslet.ui.resources.helper_preview_hidden_one
import se.pusslet.ui.resources.helper_preview_invite
import se.pusslet.ui.resources.helper_preview_note
import se.pusslet.ui.resources.helper_preview_title
import se.pusslet.ui.theme.HelperTheme
import se.pusslet.ui.theme.Spacing

/**
 * "Så ser Bengt appen" (UX plan Steg 3, parents only): the same home screen his phone shows, built from this phone's data
 * through the same rules. Nothing tapped here is saved; the first tap on something that would save says so once.
 * Navigation stays on the page, so the rows that would open something are not tappable.
 */
@Composable
fun HelperPreviewRoute(
    memberId: String,
    onBack: () -> Unit,
    /** "Bjud in Bengt" while he has no account: the invite that claims him. */
    onInvite: (memberId: String) -> Unit = {},
    viewModel: HelperPreviewViewModel = koinViewModel(),
) {
    LaunchedEffect(memberId) { viewModel.load(memberId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HelperPreviewScreen(state, onBack, onInvite = { onInvite(memberId) })
}

@Composable
fun HelperPreviewScreen(state: HelperPreviewUiState, onBack: () -> Unit, onInvite: () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val note = stringResource(Res.string.helper_preview_note)
    var reminded by rememberSaveable { mutableStateOf(false) }
    // Ja/Nej, "Jag kan inte", packing and tasks look as on his phone but write nothing; the first tap repeats the note.
    val remind: () -> Unit = {
        if (!reminded) {
            reminded = true
            scope.launch { snackbar.showSnackbar(note) }
        }
    }
    val actions = HelperActions(
        onAccept = { remind() },
        onDecline = { remind() },
        onChangeAnswer = { remind() },
        onCannotMake = { remind() },
        onTogglePacking = { _, _, _ -> remind() },
        onToggleTask = { _, _ -> remind() },
        onOpenOccurrence = null,
        onOpenWeek = null,
        onOpenShopping = null,
        onOpenSettings = null,
        onDismissHint = null,
    )
    val name = state.member?.name.orEmpty()
    Scaffold(
        topBar = { PussletTopBar(stringResource(Res.string.helper_preview_title, name), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.member == null -> Text(
                stringResource(Res.string.empty_generic),
                Modifier.padding(padding).padding(Spacing.screenHorizontal),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Banner(
                    note,
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                    kind = BannerKind.Info,
                    // Before he has joined, the preview is also where to invite him (UX plan Steg 3, §8).
                    actions = if (state.canInvite) {
                        { TextButton(onClick = onInvite) { Text(stringResource(Res.string.helper_preview_invite, name)) } }
                    } else {
                        null
                    },
                )
                Box(Modifier.weight(1f)) {
                    HelperTheme {
                        HelperHomeScreen(
                            state = state.home,
                            contentPadding = PaddingValues(),
                            actions = actions,
                            footer = if (state.hiddenThisWeek > 0) ({ HiddenLine(name, state.hiddenThisWeek) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** "Bengt ser inte 2 händelser den här veckan." */
@Composable
private fun HiddenLine(name: String, count: Int) {
    val text = if (count == 1) {
        stringResource(Res.string.helper_preview_hidden_one, name)
    } else {
        stringResource(Res.string.helper_preview_hidden, name, count)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
    )
}

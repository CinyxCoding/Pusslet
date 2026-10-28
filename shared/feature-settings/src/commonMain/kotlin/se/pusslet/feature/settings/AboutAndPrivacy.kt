package se.pusslet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.data.db.CacheDao
import se.pusslet.data.usecase.AccountDeletionPlan
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.data.usecase.DeletionOutcome
import se.pusslet.data.usecase.PrivacyOptions
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.Section
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Instant

// region About data

/** [url] null: no single page to open (subscribed calendars come from the family's own addresses). */
private data class Source(val title: StringResource, val body: StringResource, val cachePrefix: String?, val url: String?)

/** Every external source with its licence and when it was last fetched (brief §3 and §9). */
class AboutDataViewModel(private val cache: CacheDao) : ViewModel() {
    private val _fetched = MutableStateFlow<Map<String, Long>>(emptyMap())
    val fetched: StateFlow<Map<String, Long>> = _fetched.asStateFlow()

    init {
        viewModelScope.launch {
            val summaries = cache.summaries()
            _fetched.value = CACHE_PREFIXES.associateWith { prefix ->
                summaries.filter { it.key.startsWith(prefix) }.maxOfOrNull { it.fetchedAt } ?: 0L
            }.filterValues { it > 0 }
        }
    }

    companion object {
        /** `icsraw:` is the raw feed of a calendar subscription (CalendarRepository.subscriptionRawKey). */
        const val ICS_PREFIX = "icsraw:"
        val CACHE_PREFIXES = listOf("smhi:", "skolverket:", "off:", SKOLMATEN_CACHE, MATILDA_CACHE, ICS_PREFIX)
        const val SKOLMATEN_CACHE = "lunch:https://skolmaten.se"
        const val MATILDA_CACHE = "lunch:https://menu.matildaplatform.com"
    }
}

@Composable
fun AboutDataRoute(onBack: () -> Unit, viewModel: AboutDataViewModel = koinViewModel()) {
    val fetched by viewModel.fetched.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val sources = listOfNotNull(
        Source(
            Res.string.source_livsmedel,
            Res.string.source_livsmedel_body,
            null,
            "https://www.livsmedelsverket.se/om-oss/psidata/livsmedelsdatabasen",
        ),
        Source(Res.string.source_smhi, Res.string.source_smhi_body, "smhi:", "https://www.smhi.se/data/oppna-data"),
        Source(
            Res.string.source_skolverket,
            Res.string.source_skolverket_body,
            "skolverket:",
            "https://www.skolverket.se/om-oss/oppna-data",
        ),
        Source(Res.string.source_off, Res.string.source_off_body, "off:", "https://world.openfoodfacts.org"),
        Source(Res.string.source_skolmaten, Res.string.source_skolmaten_body, AboutDataViewModel.SKOLMATEN_CACHE, "https://skolmaten.se"),
        Source(
            Res.string.source_matilda,
            Res.string.source_matilda_body,
            AboutDataViewModel.MATILDA_CACHE,
            "https://www.matildafoodtech.com",
        ),
        // Only once a subscription has been fetched: a family without any has nothing to show here.
        Source(Res.string.source_ics, Res.string.source_ics_body, AboutDataViewModel.ICS_PREFIX, null)
            .takeIf { AboutDataViewModel.ICS_PREFIX in fetched },
        Source(Res.string.source_scb, Res.string.source_scb_body, null, "https://www.scb.se"),
        Source(Res.string.source_icons, Res.string.source_icons_body, null, "https://fonts.google.com/icons"),
    )
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.about_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.about_intro), style = MaterialTheme.typography.bodyLarge)
            Banner(stringResource(Res.string.about_no_ai), kind = BannerKind.Info)
            SectionCard(null) {
                sources.forEach { s ->
                    val last = s.cachePrefix?.let { fetched[it] }
                    val lastText = if (s.cachePrefix == null) {
                        null
                    } else if (last == null) {
                        stringResource(Res.string.source_never)
                    } else {
                        val dt = Instant.fromEpochMilliseconds(last).toLocalDateTime(TimeZone.currentSystemDefault())
                        stringResource(Res.string.source_last_fetch, formatDateShort(dt.date) + " " + formatTime(dt.time))
                    }
                    val url = s.url
                    ListRow(
                        title = stringResource(s.title),
                        supporting = listOfNotNull(stringResource(s.body), lastText).joinToString("\n"),
                        trailing = if (url == null) {
                            null
                        } else {
                            { TextButton(onClick = { platform.openUrl(url) }) { Text("↗") } }
                        },
                    )
                }
            }
        }
    }
}

// endregion

// region Privacy

/** What the screen is doing right now; one thing at a time. */
enum class PrivacyWork { Export, Forget, DeleteFamily, DeleteAccount }

/** The receipt after an export: the file name, or that nothing was saved (picker cancelled or the write failed). */
sealed interface ExportReceipt {
    data class Saved(val fileName: String) : ExportReceipt

    data object NotSaved : ExportReceipt
}

/** Why [work] did not go through; shown under the button that started it. */
data class PrivacyProblem(val work: PrivacyWork, val outcome: DeletionOutcome)

data class PrivacyUiState(
    val options: PrivacyOptions = PrivacyOptions(),
    val working: PrivacyWork? = null,
    val receipt: ExportReceipt? = null,
    val problem: PrivacyProblem? = null,
) {
    val busy: Boolean get() = working != null
}

/** The typed name confirms "Radera familjen för alla" when it matches, ignoring case and extra spaces. */
fun confirmsFamilyName(typed: String, familyName: String): Boolean {
    fun normal(s: String) = s.trim().split(Regex("""\s+""")).joinToString(" ").lowercase()
    return familyName.isNotBlank() && normal(typed) == normal(familyName)
}

/**
 * Integritet (BRIEF §4.12 and §6): export, "Ta bort familjen från den här telefonen", and, with a backend and an account,
 * "Radera familjen för alla" (parents) and "Radera mitt konto". After a server deletion the phone forgets the family and
 * signs out (DataPrivacy).
 */
class PrivacyViewModel(private val privacy: DataPrivacy) : ViewModel() {
    private val log = Logger.withTag("Privacy")
    private val working = MutableStateFlow<PrivacyWork?>(null)
    private val receipt = MutableStateFlow<ExportReceipt?>(null)
    private val problem = MutableStateFlow<PrivacyProblem?>(null)

    val state: StateFlow<PrivacyUiState> = combine(privacy.options, working, receipt, problem, ::PrivacyUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyUiState())

    /** Builds the file and hands it to [save] (the platform's file picker), which says whether it was written. */
    fun export(save: (fileName: String, content: String, onDone: (Boolean) -> Unit) -> Unit) {
        if (!start(PrivacyWork.Export)) return
        receipt.value = null
        viewModelScope.launch {
            val name = privacy.exportFileName()
            val content = try {
                privacy.exportJson()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Export could not be built (${e::class.simpleName})" }
                null
            }
            if (content == null) finishExport(name, saved = false) else save(name, content) { saved -> finishExport(name, saved) }
        }
    }

    fun forget(onDone: () -> Unit) {
        if (!start(PrivacyWork.Forget)) return
        viewModelScope.launch {
            val forgotten = try {
                privacy.forgetOnDevice()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Could not forget the family on this phone (${e::class.simpleName})" }
                false
            }
            working.value = null
            if (forgotten) onDone() else problem.value = PrivacyProblem(PrivacyWork.Forget, DeletionOutcome.Failed)
        }
    }

    fun deleteFamily(onDone: () -> Unit) = deleteOnServer(PrivacyWork.DeleteFamily, onDone) { privacy.deleteFamilyForEveryone() }

    fun deleteAccount(onDone: () -> Unit) = deleteOnServer(PrivacyWork.DeleteAccount, onDone) { privacy.deleteAccount() }

    private fun deleteOnServer(work: PrivacyWork, onDone: () -> Unit, call: suspend () -> DeletionOutcome) {
        if (!start(work)) return
        viewModelScope.launch {
            val outcome = call()
            working.value = null
            if (outcome == DeletionOutcome.Done) onDone() else problem.value = PrivacyProblem(work, outcome)
        }
    }

    private fun start(work: PrivacyWork): Boolean {
        if (working.value != null) return false
        working.value = work
        problem.value = null
        return true
    }

    private fun finishExport(name: String, saved: Boolean) {
        receipt.value = if (saved) ExportReceipt.Saved(name) else ExportReceipt.NotSaved
        working.value = null
    }
}

private enum class PrivacyConfirm { Forget, DeleteFamily, DeleteFamilyAgain, DeleteAccount }

@Composable
fun PrivacyRoute(onBack: () -> Unit, onForgotten: () -> Unit, viewModel: PrivacyViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    PrivacyScreen(
        state = state,
        onBack = onBack,
        onExport = { viewModel.export { name, content, done -> platform.saveFile(name, EXPORT_MIME, content, done) } },
        onForget = { viewModel.forget(onForgotten) },
        onDeleteFamily = { viewModel.deleteFamily(onForgotten) },
        onDeleteAccount = { viewModel.deleteAccount(onForgotten) },
    )
}

private const val EXPORT_MIME = "application/json"

@Composable
fun PrivacyScreen(
    state: PrivacyUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onForget: () -> Unit,
    onDeleteFamily: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var confirm by remember { mutableStateOf<PrivacyConfirm?>(null) }
    val options = state.options
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.privacy_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.privacy_summary), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.privacy_sensitive), style = MaterialTheme.typography.bodyMedium)
                Banner(stringResource(Res.string.about_no_ai), kind = BannerKind.Info)
            }
            Section(stringResource(Res.string.privacy_export)) {
                Body(stringResource(Res.string.privacy_export_body))
                ActionButton(stringResource(Res.string.privacy_export_action), enabled = !state.busy, onClick = onExport)
                when (val r = state.receipt) {
                    is ExportReceipt.Saved -> Quiet(stringResource(Res.string.privacy_export_saved, r.fileName))
                    ExportReceipt.NotSaved -> Quiet(stringResource(Res.string.privacy_export_not_saved))
                    null -> Unit
                }
            }
            Section(stringResource(Res.string.privacy_this_phone)) {
                Body(stringResource(if (options.server) Res.string.privacy_forget_body else Res.string.privacy_forget_body_local))
                ActionButton(stringResource(Res.string.privacy_forget_device), enabled = !state.busy) { confirm = PrivacyConfirm.Forget }
                ProblemFor(PrivacyWork.Forget, state)
            }
            if (options.needsSignIn) Quiet(stringResource(Res.string.privacy_sign_in_to_delete))
            if (options.canDeleteFamily) {
                Section(stringResource(Res.string.privacy_delete_family)) {
                    Body(stringResource(Res.string.privacy_delete_family_body))
                    if (state.working == PrivacyWork.DeleteFamily) {
                        Deleting()
                    } else {
                        ActionButton(stringResource(Res.string.privacy_delete_family_action), enabled = !state.busy) {
                            confirm = PrivacyConfirm.DeleteFamily
                        }
                    }
                    ProblemFor(PrivacyWork.DeleteFamily, state)
                }
            }
            if (options.canDeleteAccount) {
                Section(stringResource(Res.string.privacy_delete_account)) {
                    Body(stringResource(accountBody(options.accountPlan)))
                    if (state.working == PrivacyWork.DeleteAccount) {
                        Deleting()
                    } else if (options.accountPlan != AccountDeletionPlan.LastParent) {
                        ActionButton(stringResource(Res.string.privacy_delete_account_action), enabled = !state.busy) {
                            confirm = PrivacyConfirm.DeleteAccount
                        }
                    }
                    ProblemFor(PrivacyWork.DeleteAccount, state)
                }
            }
        }
    }
    when (confirm) {
        PrivacyConfirm.Forget -> ConfirmDialog(
            text = stringResource(if (options.server) Res.string.privacy_forget_body else Res.string.privacy_delete_confirm),
            confirmLabel = stringResource(Res.string.action_confirm),
            onConfirm = {
                confirm = null
                onForget()
            },
            onDismiss = { confirm = null },
        )
        PrivacyConfirm.DeleteFamily -> if (options.familyName.isBlank()) {
            // No name to type: confirm twice instead.
            ConfirmDialog(
                title = stringResource(Res.string.privacy_delete_family),
                text = stringResource(Res.string.privacy_delete_family_body),
                confirmLabel = stringResource(Res.string.action_confirm),
                onConfirm = { confirm = PrivacyConfirm.DeleteFamilyAgain },
                onDismiss = { confirm = null },
            )
        } else {
            DeleteFamilyDialog(
                familyName = options.familyName,
                onConfirm = {
                    confirm = null
                    onDeleteFamily()
                },
                onDismiss = { confirm = null },
            )
        }
        PrivacyConfirm.DeleteFamilyAgain -> ConfirmDialog(
            text = stringResource(Res.string.privacy_delete_confirm),
            confirmLabel = stringResource(Res.string.privacy_delete_family_action),
            onConfirm = {
                confirm = null
                onDeleteFamily()
            },
            onDismiss = { confirm = null },
        )
        PrivacyConfirm.DeleteAccount -> ConfirmDialog(
            title = stringResource(Res.string.privacy_delete_account_confirm_title),
            text = stringResource(Res.string.privacy_delete_confirm),
            confirmLabel = stringResource(Res.string.privacy_delete_account_action),
            onConfirm = {
                confirm = null
                onDeleteAccount()
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

private fun accountBody(plan: AccountDeletionPlan): StringResource = when (plan) {
    AccountDeletionPlan.LeaveFamily -> Res.string.privacy_delete_account_body
    AccountDeletionPlan.DeletesFamily -> Res.string.privacy_delete_account_alone
    AccountDeletionPlan.LastParent -> Res.string.privacy_delete_account_last_parent
}

private fun problemText(outcome: DeletionOutcome): StringResource = when (outcome) {
    DeletionOutcome.LastParent -> Res.string.privacy_delete_account_last_parent
    DeletionOutcome.NotAllowed -> Res.string.privacy_problem_not_allowed
    DeletionOutcome.SignInAgain -> Res.string.privacy_problem_sign_in
    DeletionOutcome.Offline -> Res.string.privacy_problem_offline
    DeletionOutcome.Failed, DeletionOutcome.Done -> Res.string.privacy_problem_failed
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
    ) { Text(label) }
}

@Composable
private fun Deleting() {
    Row(
        Modifier.heightIn(min = Spacing.primaryTouchTarget).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.privacy_deleting), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProblemFor(work: PrivacyWork, state: PrivacyUiState) {
    val problem = state.problem?.takeIf { it.work == work } ?: return
    Banner(stringResource(problemText(problem.outcome)), kind = BannerKind.Info)
}

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, title: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = if (title == null) {
            null
        } else {
            { Text(title) }
        },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** The one "Är du säker?" in the app worth having: typing the family's name before it is deleted for everyone. */
@Composable
private fun DeleteFamilyDialog(familyName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.privacy_delete_family_confirm_title, familyName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(Res.string.privacy_delete_family_confirm))
                OutlinedTextField(
                    typed,
                    { typed = it },
                    label = { Text(stringResource(Res.string.privacy_family_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmsFamilyName(typed, familyName)) {
                Text(stringResource(Res.string.privacy_delete_family_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

// endregion

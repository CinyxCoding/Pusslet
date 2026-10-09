package se.pusslet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.data.BackendConfig
import se.pusslet.data.BackendMode
import se.pusslet.data.db.CacheDao
import se.pusslet.data.usecase.DataPrivacy
import se.pusslet.ui.components.Banner
import se.pusslet.ui.components.BannerKind
import se.pusslet.ui.components.ListRow
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Instant

// region About data

private data class Source(val title: StringResource, val body: StringResource, val cachePrefix: String?, val url: String)

private const val SKOLMATEN_CACHE = "lunch:https://skolmaten.se"
private const val MATILDA_CACHE = "lunch:https://menu.matildaplatform.com"

/** Every external source with its licence and when it was last fetched (brief §3 and §9). */
class AboutDataViewModel(private val cache: CacheDao) : ViewModel() {
    private val _fetched = MutableStateFlow<Map<String, Long>>(emptyMap())
    val fetched: StateFlow<Map<String, Long>> = _fetched.asStateFlow()

    init {
        viewModelScope.launch {
            val summaries = cache.summaries()
            _fetched.value = listOf("smhi:", "skolverket:", "off:", SKOLMATEN_CACHE, MATILDA_CACHE, "icsraw:").associateWith { prefix ->
                summaries.filter { it.key.startsWith(prefix) }.maxOfOrNull { it.fetchedAt } ?: 0L
            }.filterValues { it > 0 }
        }
    }
}

@Composable
fun AboutDataRoute(onBack: () -> Unit, viewModel: AboutDataViewModel = koinViewModel()) {
    val fetched by viewModel.fetched.collectAsStateWithLifecycle()
    val platform = LocalPlatformActions.current
    val sources = listOf(
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
        Source(Res.string.source_skolmaten, Res.string.source_skolmaten_body, SKOLMATEN_CACHE, "https://skolmaten.se"),
        Source(Res.string.source_matilda, Res.string.source_matilda_body, MATILDA_CACHE, "https://www.matildafoodtech.com"),
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
                    ListRow(
                        title = stringResource(s.title),
                        supporting = listOfNotNull(stringResource(s.body), lastText).joinToString("\n"),
                        trailing = { TextButton(onClick = { platform.openUrl(s.url) }) { Text("↗") } },
                    )
                }
            }
        }
    }
}

// endregion

// region Privacy

enum class PrivacyAction { Forget, DeleteFamily, DeleteAccount }

class PrivacyViewModel(private val privacy: DataPrivacy, config: BackendConfig) : ViewModel() {
    /** The public policy (built from docs/PRIVACY.md and hosted next to the calendar links); none without a server. */
    val policyUrl: String? = if (config.mode == BackendMode.Firebase) "https://${config.linkHost}/privacy/" else null

    private val log = co.touchlab.kermit.Logger.withTag("Privacy")
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val failed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val canDeleteOnServer: Boolean get() = privacy.canDeleteOnServer
    val isParent: Boolean get() = privacy.isParent

    fun export(onReady: (String, String) -> Unit) {
        viewModelScope.launch { onReady(privacy.exportFileName(), privacy.exportJson()) }
    }

    fun run(action: PrivacyAction, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            failed.value = false
            try {
                when (action) {
                    PrivacyAction.Forget -> privacy.forgetOnDevice()
                    PrivacyAction.DeleteFamily -> privacy.deleteFamilyEverywhere()
                    PrivacyAction.DeleteAccount -> privacy.deleteAccount()
                }
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was removed locally: the user can try again when online.
                log.w { "Privacy action $action failed: ${e::class.simpleName}" }
                failed.value = true
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun PrivacyRoute(onBack: () -> Unit, onForgotten: () -> Unit, viewModel: PrivacyViewModel = koinViewModel()) {
    val platform = LocalPlatformActions.current
    var confirm by remember { mutableStateOf<PrivacyAction?>(null) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.privacy_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.privacy_summary), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(Res.string.privacy_sensitive), style = MaterialTheme.typography.bodyMedium)
            Banner(stringResource(Res.string.about_no_ai), kind = BannerKind.Info)
            viewModel.policyUrl?.let { url ->
                TextButton(onClick = { platform.openUrl(url) }) { Text(stringResource(Res.string.privacy_policy_link)) }
            }
            SectionCard(stringResource(Res.string.privacy_export)) {
                Text(stringResource(Res.string.privacy_export_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { viewModel.export { name, json -> platform.saveFile(name, "application/json", json) {} } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.privacy_export)) }
            }
            SectionCard(stringResource(Res.string.privacy_forget_device)) {
                Text(stringResource(Res.string.privacy_forget_device_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { confirm = PrivacyAction.Forget },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                ) { Text(stringResource(Res.string.privacy_forget_device)) }
            }
            if (viewModel.canDeleteOnServer) {
                if (viewModel.isParent) {
                    SectionCard(stringResource(Res.string.privacy_delete_family)) {
                        Text(stringResource(Res.string.privacy_delete_family_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { confirm = PrivacyAction.DeleteFamily },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                        ) { Text(stringResource(Res.string.privacy_delete_family)) }
                    }
                }
                SectionCard(stringResource(Res.string.privacy_delete_account)) {
                    Text(stringResource(Res.string.privacy_delete_account_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { confirm = PrivacyAction.DeleteAccount },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
                    ) { Text(stringResource(Res.string.privacy_delete_account)) }
                }
            } else {
                Text(stringResource(Res.string.privacy_local_only), style = MaterialTheme.typography.bodySmall)
            }
            if (failed) Text(stringResource(Res.string.privacy_failed), color = MaterialTheme.colorScheme.error)
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = {
                Text(
                    stringResource(
                        if (action ==
                            PrivacyAction.Forget
                        ) {
                            Res.string.privacy_forget_confirm
                        } else {
                            Res.string.privacy_delete_confirm
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.run(action, onForgotten)
                }) { Text(stringResource(Res.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

// endregion

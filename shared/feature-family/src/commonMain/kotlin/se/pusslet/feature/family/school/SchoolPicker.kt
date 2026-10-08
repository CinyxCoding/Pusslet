package se.pusslet.feature.family.school

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.Municipalities
import se.pusslet.core.model.Municipality
import se.pusslet.core.model.SchoolKind
import se.pusslet.core.model.SchoolRef
import se.pusslet.data.sources.SchoolRegistry
import se.pusslet.data.sources.SchoolUnitSummary
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

data class SchoolPickerState(
    val municipalityQuery: String = "",
    val municipality: Municipality? = null,
    val schoolQuery: String = "",
    val schools: List<SchoolUnitSummary> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Kommun -> skola via Skolverket (ADR-010). Free text always works (preschools are not in the register). */
class SchoolPickerViewModel(private val registry: SchoolRegistry) : ViewModel() {
    private val _state = MutableStateFlow(SchoolPickerState())
    val state: StateFlow<SchoolPickerState> = _state.asStateFlow()
    private var job: Job? = null

    fun setMunicipalityQuery(q: String) = _state.update { it.copy(municipalityQuery = q) }

    fun chooseMunicipality(m: Municipality?) {
        _state.update { it.copy(municipality = m, schools = emptyList(), schoolQuery = "") }
        if (m != null) search()
    }

    fun setSchoolQuery(q: String) {
        _state.update { it.copy(schoolQuery = q) }
        job?.cancel()
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search()
        }
    }

    private fun search() {
        val m = _state.value.municipality ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val result = registry.search(m.code, _state.value.schoolQuery)
            _state.update { it.copy(loading = false, schools = result?.value.orEmpty().take(MAX_RESULTS), failed = result == null) }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_RESULTS = 60
    }
}

@Composable
fun SchoolPickerDialog(
    onDismiss: () -> Unit,
    onPicked: (SchoolRef) -> Unit,
    viewModel: SchoolPickerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        title = { Text(stringResource(Res.string.child_school)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val m = state.municipality
                if (m == null) {
                    OutlinedTextField(
                        value = state.municipalityQuery,
                        onValueChange = viewModel::setMunicipalityQuery,
                        label = { Text(stringResource(Res.string.school_municipality)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(Municipalities.search(state.municipalityQuery).take(MAX_MUNICIPALITIES), key = { it.code }) { muni ->
                            PickRow(muni.name) { viewModel.chooseMunicipality(muni) }
                        }
                    }
                } else {
                    TextButton(onClick = { viewModel.chooseMunicipality(null) }) { Text(m.name) }
                    OutlinedTextField(
                        value = state.schoolQuery,
                        onValueChange = viewModel::setSchoolQuery,
                        label = { Text(stringResource(Res.string.school_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.failed) Text(stringResource(Res.string.school_offline), style = MaterialTheme.typography.bodySmall)
                    val typed = state.schoolQuery.trim()
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (typed.isNotEmpty()) {
                            item(key = "free") {
                                PickRow(stringResource(Res.string.school_free_text, typed)) {
                                    onPicked(
                                        SchoolRef(
                                            typed,
                                            municipalityCode = m.code,
                                            kind =
                                                SchoolKind.Preschool.takeIf { "förskol" in typed.lowercase() } ?: SchoolKind.Other,
                                        ),
                                    )
                                }
                            }
                        }
                        items(state.schools, key = { it.code }) { s ->
                            PickRow(s.name) { onPicked(SchoolRef(s.name, s.code, m.code, SchoolKind.School)) }
                        }
                    }
                    Text(
                        stringResource(Res.string.school_preschool_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(Res.string.school_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun PickRow(text: String, onClick: () -> Unit) {
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().heightIn(
                min = 48.dp,
            ).clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.md),
        )
        HorizontalDivider()
    }
}

private const val MAX_MUNICIPALITIES = 40

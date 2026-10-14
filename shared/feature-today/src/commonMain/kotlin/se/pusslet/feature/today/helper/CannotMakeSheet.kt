package se.pusslet.feature.today.helper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.helper.HelperMission
import se.pusslet.core.model.FamilyPerson
import se.pusslet.feature.today.SEPARATOR
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.helper_cannot_send
import se.pusslet.ui.resources.helper_cannot_send_family
import se.pusslet.ui.resources.helper_cannot_title
import se.pusslet.ui.theme.HelperDimens
import se.pusslet.ui.theme.Spacing

/**
 * "Kan du inte idag?": today's missions that are still to come, all chosen from the start. "Säg till Sara och Johan" asks
 * the family to take over each chosen one (a request from me to anyone, ADR-030); nothing in the event changes.
 */
@Composable
internal fun CannotMakeSheet(
    missions: List<HelperMission>,
    parents: List<FamilyPerson>,
    onSend: (List<HelperMission>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keys, so the choice survives a rotation; a mission that has passed meanwhile simply drops out.
    var chosen by rememberSaveable { mutableStateOf(missions.map { it.key }) }
    // Large text needs the whole height; a half-open sheet would hide the button.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(HelperDimens.gap),
        ) {
            Text(
                stringResource(Res.string.helper_cannot_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            missions.forEach { m ->
                CheckRow(
                    text = missionText(m).text,
                    checked = m.key in chosen,
                    onCheckedChange = { on -> chosen = if (on) chosen + m.key else chosen - m.key },
                    modifier = Modifier.heightIn(min = HelperDimens.row),
                    supporting = listOfNotNull(m.title, m.place).joinToString(SEPARATOR).ifEmpty { null },
                )
            }
            val send = if (parents.isEmpty()) {
                stringResource(Res.string.helper_cannot_send_family)
            } else {
                stringResource(Res.string.helper_cannot_send, names(parents))
            }
            Button(
                onClick = { onSend(missions.filter { it.key in chosen }) },
                enabled = missions.any { it.key in chosen },
                modifier = Modifier.fillMaxWidth().heightIn(min = HelperDimens.button),
            ) { Text(send) }
        }
    }
}

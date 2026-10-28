package se.pusslet.feature.today.medicine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.medicine.MedicineRules
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.resources.*

/**
 * "Medicin idag" on Idag (ADR-050): today's planned doses of every course, "Given" on those not yet given, and who gave
 * the others. "Logg" opens the medicine view.
 */
@Composable
fun MedicineTodayCard(rows: List<MedicineDoseRow>, onGive: (MedicineRules.Slot) -> Unit, onOpen: () -> Unit) {
    SectionCard(
        stringResource(Res.string.medicine_today),
        action = { TextButton(onClick = onOpen) { Text(stringResource(Res.string.medicine_log)) } },
    ) {
        rows.forEach { row ->
            val course = row.slot.course
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val what = listOf(course.medicine, course.dose).filter { it.isNotBlank() }.joinToString(" ")
                    Text(
                        stringResource(Res.string.medicine_dose_row, formatTime(row.slot.at.time), what, row.child.name),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val given = row.slot.given
                    if (given != null) {
                        Text(
                            row.givenBy?.let { stringResource(Res.string.medicine_given_by, it.name) }
                                ?: stringResource(Res.string.medicine_given),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.slot.given == null) {
                    OutlinedButton(onClick = { onGive(row.slot) }) { Text(stringResource(Res.string.medicine_given)) }
                }
            }
        }
    }
}

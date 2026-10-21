package se.pusslet.feature.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.PantryLocation
import se.pusslet.core.units.QuantityParser
import se.pusslet.core.units.Units
import se.pusslet.ui.components.DatePickerSheet
import se.pusslet.ui.components.OutlinedValueField
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.format.formatDateLong
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/**
 * Edit one thing at home: name, amount with unit (empty = "Finns hemma", amount unknown), the place (Skafferi, Kyl, Frys)
 * and an optional best-before date. "Ta bort" offers "Ångra" on the screen underneath.
 */
@Composable
internal fun PantryEditorSheet(
    item: PantryItem,
    today: LocalDate?,
    onDismiss: () -> Unit,
    onSave: (PantryItem) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var amount by rememberSaveable(item.id) { mutableStateOf(item.quantity?.let(Units::formatNumber).orEmpty()) }
    var unit by remember(item.id) { mutableStateOf(item.unit) }
    var location by remember(item.id) { mutableStateOf(item.location) }
    var bestBefore by remember(item.id) { mutableStateOf(item.bestBefore) }
    var pickDate by remember { mutableStateOf(false) }
    val parsedAmount = amount.trim().takeIf { it.isNotEmpty() }?.let { QuantityParser.parsePrefix(it).quantity?.value }
    val amountInvalid = amount.isNotBlank() && parsedAmount == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(Res.string.shopping_edit_item), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.shopping_item_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(Res.string.shopping_amount)) },
                    supportingText = { Text(stringResource(Res.string.pantry_amount_help)) },
                    singleLine = true,
                    isError = amountInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                UnitPicker(unit, onPick = { unit = it }, modifier = Modifier.weight(1f).padding(top = Spacing.xs))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PantryLocation.entries.forEachIndexed { index, place ->
                    SegmentedButton(
                        selected = location == place,
                        onClick = { location = place },
                        shape = SegmentedButtonDefaults.itemShape(index, PantryLocation.entries.size),
                        colors = SelectionColors.segmentedButton(),
                        label = { Text(stringResource(locationLabel(place)), maxLines = 1) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedValueField(
                    label = stringResource(Res.string.pantry_best_before_label),
                    value = bestBefore?.let { formatDateLong(it) },
                    onClick = { pickDate = true },
                    placeholder = stringResource(Res.string.pantry_best_before_none),
                )
                if (bestBefore != null) {
                    TextButton(onClick = { bestBefore = null }) { Text(stringResource(Res.string.pantry_best_before_clear)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.action_delete)) }
                Button(
                    onClick = {
                        onSave(
                            item.copy(
                                name = name.trim(),
                                quantity = parsedAmount,
                                unit = if (parsedAmount == null) null else unit,
                                location = location,
                                bestBefore = bestBefore,
                            ),
                        )
                    },
                    enabled = name.isNotBlank() && !amountInvalid,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.action_save)) }
            }
        }
    }
    if (pickDate) {
        val initial = bestBefore ?: today
        if (initial != null) {
            DatePickerSheet(initial, onDismiss = { pickDate = false }, onPicked = { bestBefore = it })
        }
    }
}

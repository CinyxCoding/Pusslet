package se.pusslet.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.SickDay
import se.pusslet.core.model.VabPart
import se.pusslet.core.sick.SickDays
import se.pusslet.core.time.plusDays
import se.pusslet.ui.components.CheckRow
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.components.SwitchRow
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.formatTime
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing

/** What a tap on the sick-day cards can do (ADR-047). The defaults do nothing, for previews and screenshot tests. */
data class SickActions(
    val onReport: (childIds: List<String>, date: LocalDate) -> Unit = { _, _ -> },
    val onUndo: (SickDay) -> Unit = {},
    val onHomeWith: (SickDay, String?) -> Unit = { _, _ -> },
    val onVab: (SickDay, Boolean) -> Unit = { _, _ -> },
    val onVabPart: (SickDay, VabPart) -> Unit = { _, _ -> },
    val onAbsenceReported: (SickDay, Boolean) -> Unit = { _, _ -> },
    val onWorkTold: (SickDay, Boolean) -> Unit = { _, _ -> },
    val onVabReportedDay: (SickDay, Boolean) -> Unit = { _, _ -> },
    val onStillSick: (SickDay) -> Unit = {},
    val onWellAgain: (SickDay) -> Unit = {},
    val onVabReported: (SickDays.VabToReport) -> Unit = {},
    val onOpenForsakringskassan: () -> Unit = {},
    /** "Medicin och feber" for the sick child (ADR-050). */
    val onOpenMedicine: (childId: String) -> Unit = {},
)

/** "Vem är sjuk?": the children to report, today or tomorrow, and what that does. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SickReportSheet(children: List<FamilyPerson>, today: LocalDate, onReport: (List<String>, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by rememberSaveable { mutableStateOf(if (children.size == 1) listOf(children.first().id) else emptyList()) }
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.sick_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                children.forEach { child ->
                    val selected = child.id in chosen
                    FilterChip(
                        selected = selected,
                        onClick = { chosen = if (selected) chosen - child.id else chosen + child.id },
                        label = { Text(child.name) },
                        colors = SelectionColors.filterChip(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = !tomorrow,
                    onClick = { tomorrow = false },
                    label = { Text(stringResource(Res.string.today)) },
                    colors = SelectionColors.filterChip(),
                )
                FilterChip(
                    selected = tomorrow,
                    onClick = { tomorrow = true },
                    label = { Text(stringResource(Res.string.tomorrow)) },
                    colors = SelectionColors.filterChip(),
                )
            }
            Text(
                stringResource(Res.string.sick_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onReport(chosen, if (tomorrow) today.plusDays(1) else today) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryTouchTarget),
            ) { Text(stringResource(Res.string.sick_report_confirm)) }
        }
    }
}

/** "Ella är sjuk idag": what is off, who stays home, VAB and the three reports. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SickCardView(card: SickCard, sick: SickToday, actions: SickActions) {
    val day = card.day
    val title = stringResource(if (card.tomorrow) Res.string.sick_card_title_tomorrow else Res.string.sick_card_title, card.child.name)
    SectionCard(title, action = { TextButton(onClick = { actions.onUndo(day) }) { Text(stringResource(Res.string.action_undo)) } }) {
        if (card.cancelled.isNotEmpty()) {
            val what = joinNames(
                card.cancelled.map { o ->
                    if (o.allDay) o.event.title else "${o.event.title} ${formatTime(o.start.time)}"
                },
            )
            Text(stringResource(Res.string.sick_cancelled, what), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(Res.string.sick_home_with), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            sick.adults.forEach { adult ->
                val selected = day.homeWith == adult.id
                FilterChip(
                    selected = selected,
                    onClick = { actions.onHomeWith(day, if (selected) null else adult.id) },
                    label = { Text(adult.name) },
                    colors = SelectionColors.filterChip(),
                )
            }
        }
        if (card.homeWithIsParent) {
            SwitchRow(stringResource(Res.string.sick_vab), day.vab, { actions.onVab(day, it) })
            if (day.vab) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VabPart.entries.forEach { part ->
                        FilterChip(
                            selected = day.vabPart == part,
                            onClick = { actions.onVabPart(day, part) },
                            label = { Text(stringResource(vabPartLabel(part))) },
                            colors = SelectionColors.filterChip(),
                        )
                    }
                }
            }
        }
        CheckRow(
            text = card.school?.let { stringResource(Res.string.sick_absence_reported, it) }
                ?: stringResource(Res.string.sick_absence_reported_school),
            checked = day.absenceReported,
            onCheckedChange = { actions.onAbsenceReported(day, it) },
        )
        if (card.homeWithIsParent && day.vab) {
            CheckRow(stringResource(Res.string.sick_work_told), day.workTold, { actions.onWorkTold(day, it) })
            CheckRow(stringResource(Res.string.sick_vab_reported), day.vabReported, { actions.onVabReportedDay(day, it) })
        }
        VabThisYear(sick.vabThisYear)
        TextButton(onClick = { actions.onOpenMedicine(card.child.id) }) { Text(stringResource(Res.string.medicine_title)) }
    }
}

/** "VAB i år: Sara 4,5 · Johan 2". */
@Composable
fun VabThisYear(counts: List<Pair<FamilyPerson, Double>>) {
    if (counts.isEmpty()) return
    val separator = stringResource(Res.string.decimal_separator).first()
    val people = counts.map { (p, days) -> stringResource(Res.string.sick_vab_person, p.name, SickModel.days(days, separator)) }
    Text(
        stringResource(Res.string.sick_vab_year, people.joinToString(" · ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Är Ella fortfarande sjuk?" the next morning. */
@Composable
fun StillSickView(card: StillSickCard, actions: SickActions) {
    SectionCard(stringResource(Res.string.sick_still_title, card.child.name)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = {
                actions.onStillSick(card.day)
            }, modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.sick_still_yes))
            }
            OutlinedButton(onClick = {
                actions.onWellAgain(card.day)
            }, modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(Res.string.sick_still_no))
            }
        }
    }
}

/** "Anmäl VAB för Ella · 2 dagar · 20 okt–21 okt" once the child is well, until it is reported. */
@Composable
fun VabReportView(card: VabReportCard, actions: SickActions) {
    val report = card.report
    val range = if (report.first == report.last) {
        formatDateShort(report.first)
    } else {
        stringResource(Res.string.calendar_range, formatDateShort(report.first), formatDateShort(report.last))
    }
    val separator = stringResource(Res.string.decimal_separator).first()
    val body = if (report.days.size == 1 && report.total == 1.0) {
        stringResource(Res.string.sick_vab_report_one, range)
    } else {
        stringResource(Res.string.sick_vab_report_days, SickModel.days(report.total, separator), range)
    }
    SectionCard(stringResource(Res.string.sick_vab_report_title, card.child.name)) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.onOpenForsakringskassan) { Text(stringResource(Res.string.sick_vab_report_open)) }
            TextButton(onClick = { actions.onVabReported(report) }) { Text(stringResource(Res.string.sick_vab_report_done)) }
        }
    }
}

private fun vabPartLabel(part: VabPart): StringResource = when (part) {
    VabPart.Whole -> Res.string.sick_vab_whole
    VabPart.ThreeQuarters -> Res.string.sick_vab_three_quarters
    VabPart.Half -> Res.string.sick_vab_half
    VabPart.Quarter -> Res.string.sick_vab_quarter
    VabPart.Eighth -> Res.string.sick_vab_eighth
}

/** Försäkringskassan's start page; the VAB service moves now and then, the start page does not. */
const val FORSAKRINGSKASSAN_URL = "https://www.forsakringskassan.se"

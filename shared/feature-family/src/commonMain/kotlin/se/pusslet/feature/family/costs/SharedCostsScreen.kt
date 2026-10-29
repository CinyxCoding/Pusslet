package se.pusslet.feature.family.costs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.costs.SharedCosts
import se.pusslet.core.model.ExpenseCategory
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Role
import se.pusslet.core.model.SharedExpense
import se.pusslet.data.repository.ExpenseRepository
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.OtherHomeSharing
import se.pusslet.data.usecase.SharedSpace
import se.pusslet.ui.components.DateField
import se.pusslet.ui.components.EmptyState
import se.pusslet.ui.components.PussletTopBar
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.components.SelectionColors
import se.pusslet.ui.components.SheetDragHandle
import se.pusslet.ui.format.formatDateShort
import se.pusslet.ui.format.joinNames
import se.pusslet.ui.resources.*
import se.pusslet.ui.theme.Spacing
import kotlin.time.Clock

/** One space's costs as the view shows them (ADR-057). */
data class SpaceCosts(
    val space: SharedSpace,
    val otherHome: String?,
    /** Positive: the other home owes us; negative: we owe them. Öre. */
    val balance: Long,
    val expenses: List<SharedExpense>,
    val byCategory: List<Pair<ExpenseCategory, Long>>,
)

data class SharedCostsUiState(
    val loading: Boolean = true,
    val canEdit: Boolean = false,
    val myHome: String? = null,
    val spaces: List<SpaceCosts> = emptyList(),
    /** Member id -> name, ours and the other home's, for "Lina betalade". */
    val names: Map<String, FamilyPerson> = emptyMap(),
    val today: LocalDate? = null,
)

/** "Delade kostnader" (ADR-057): what the homes spend on the children they share, and who owes whom. Parents only. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedCostsViewModel(
    sharing: OtherHomeSharing,
    private val expenses: ExpenseRepository,
    family: FamilyRepository,
    private val sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val perSpace = sharing.spaces.flatMapLatest { spaces ->
        if (spaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(spaces.map { s -> expenses.expenses(s.id).let { f -> combine(f, flowOf(s)) { e, sp -> sp to e } } }) { it.toList() }
        }
    }

    val state: StateFlow<SharedCostsUiState> = combine(perSpace, family.everyone, sessions.session) { list, people, s ->
        val home = s.familyId
        SharedCostsUiState(
            loading = false,
            canEdit = s.role == Role.Parent,
            myHome = home,
            spaces = list.map { (space, e) ->
                SpaceCosts(
                    space = space,
                    otherHome = space.homes.firstOrNull { it != home },
                    balance = home?.let { SharedCosts.balance(e, it) } ?: 0,
                    expenses = e,
                    byCategory = SharedCosts.byCategory(e),
                )
            },
            names = people.associateBy { it.id },
            today = clock.todayIn(zone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SharedCostsUiState())

    @Suppress("LongParameterList")
    fun add(
        space: SpaceCosts,
        title: String,
        amountOre: Long,
        date: LocalDate,
        wePaid: Boolean,
        otherSharePercent: Int,
        category: ExpenseCategory,
    ) {
        val home = state.value.myHome ?: return
        val payer = if (wePaid) home else space.otherHome ?: return
        if (!state.value.canEdit) return
        viewModelScope.launch {
            expenses.add(space.space.id, title, amountOre, date, payer, otherSharePercent, category, space.space.children.map { it.id })
        }
    }

    fun settle(space: SpaceCosts, title: String) {
        val other = space.otherHome ?: return
        if (!state.value.canEdit) return
        viewModelScope.launch { expenses.settle(space.space.id, title, clock.todayIn(zone), other, space.balance) }
    }

    fun delete(expense: SharedExpense) {
        if (!state.value.canEdit || !SharedCosts.canRemove(expense, state.value.myHome)) return
        viewModelScope.launch { expenses.delete(expense.id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun SharedCostsRoute(onBack: () -> Unit, onOtherHome: () -> Unit, viewModel: SharedCostsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SharedCostsScreen(state, onBack, onOtherHome, viewModel::add, viewModel::settle, viewModel::delete)
}

@Composable
fun SharedCostsScreen(
    state: SharedCostsUiState,
    onBack: () -> Unit,
    onOtherHome: () -> Unit,
    onAdd: (SpaceCosts, String, Long, LocalDate, Boolean, Int, ExpenseCategory) -> Unit,
    onSettle: (SpaceCosts, String) -> Unit,
    onDelete: (SharedExpense) -> Unit,
) {
    var adding by remember { mutableStateOf<SpaceCosts?>(null) }
    var deleting by remember { mutableStateOf<SharedExpense?>(null) }
    val decimal = stringResource(Res.string.decimal_separator).first()
    Scaffold(topBar = { PussletTopBar(stringResource(Res.string.costs_title), onBack = onBack) }) { padding ->
        val today = state.today
        if (state.loading || today == null) return@Scaffold
        if (!state.canEdit) {
            EmptyState(message = stringResource(Res.string.costs_parents_only), modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (state.spaces.isEmpty()) {
            EmptyState(
                message = stringResource(Res.string.costs_no_share),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onOtherHome) { Text(stringResource(Res.string.family_other_home)) } },
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(Res.string.costs_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.spaces.forEach { s ->
                item(key = "space-${s.space.id}") { SpaceCard(s, decimal, { adding = s }, onSettle) }
                items(s.expenses, key = { it.id }) { e -> ExpenseRow(e, state, decimal) { deleting = e } }
            }
        }
    }
    adding?.let { s ->
        AddExpenseSheet(state.today ?: return, decimal, onDismiss = { adding = null }) { title, amount, date, we, share, category ->
            adding = null
            onAdd(s, title, amount, date, we, share, category)
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(Res.string.costs_delete_confirm, e.title)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(e)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SpaceCard(s: SpaceCosts, decimal: Char, onAdd: () -> Unit, onSettle: (SpaceCosts, String) -> Unit) {
    val children = joinNames(s.space.children.map { it.firstName })
    val other = joinNames(
        s.space.otherAdults.map {
            it.nickname ?: it.displayName
        },
    ).ifEmpty { stringResource(Res.string.family_other_home) }
    SectionCard(children) {
        val amount = SharedCosts.format(kotlin.math.abs(s.balance), decimal)
        Text(
            when {
                s.balance > 0 -> stringResource(Res.string.costs_they_owe, other, amount)
                s.balance < 0 -> stringResource(Res.string.costs_we_owe, other, amount)
                else -> stringResource(Res.string.costs_even)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (s.byCategory.isNotEmpty()) {
            Text(
                s.byCategory.map { (c, ore) ->
                    stringResource(Res.string.costs_category_line, categoryName(c), SharedCosts.format(ore, decimal))
                }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = onAdd, enabled = s.otherHome != null) { Text(stringResource(Res.string.costs_add)) }
            val settleTitle = stringResource(Res.string.costs_settlement)
            if (s.balance != 0L) OutlinedButton(onClick = { onSettle(s, settleTitle) }) { Text(stringResource(Res.string.costs_settle)) }
        }
        if (s.otherHome == null) Text(stringResource(Res.string.other_home_waiting), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExpenseRow(e: SharedExpense, state: SharedCostsUiState, decimal: Char, onDelete: () -> Unit) {
    val ours = e.paidByHome == state.myHome
    val who = e.paidBy?.let { state.names[it]?.name } ?: stringResource(if (ours) Res.string.costs_paid_us else Res.string.costs_paid_them)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.costs_expense_line, e.title, SharedCosts.format(e.amountOre, decimal)),
                style = MaterialTheme.typography.bodyLarge,
            )
            val split = if (e.settlement) {
                ""
            } else {
                " · " +
                    stringResource(Res.string.costs_split_line, 100 - e.otherSharePercent, e.otherSharePercent)
            }
            // Who wrote it down, so both homes see who added what (ADR-059).
            val added = e.createdBy?.let { state.names[it]?.name }?.let { " · " + stringResource(Res.string.costs_added_by, it) }.orEmpty()
            Text(
                "${formatDateShort(e.date)} · ${stringResource(Res.string.costs_paid_by, who)}$split$added",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only the home that added a cost removes it (ADR-059).
        if (SharedCosts.canRemove(e, state.myHome)) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_delete)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddExpenseSheet(
    today: LocalDate,
    decimal: Char,
    onDismiss: () -> Unit,
    onSave: (String, Long, LocalDate, Boolean, Int, ExpenseCategory) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(today) }
    var wePaid by rememberSaveable { mutableStateOf(true) }
    var share by rememberSaveable { mutableStateOf(HALF) }
    var category by rememberSaveable { mutableStateOf(ExpenseCategory.Other) }
    val amount = SharedCosts.parse(amountText)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(Res.string.costs_add),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                },
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_TITLE) },
                label = { Text(stringResource(Res.string.costs_what)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(Res.string.costs_amount)) },
                isError = amountText.isNotBlank() && amount == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(stringResource(Res.string.field_date), date, { date = it })
            Chips(listOf(true to Res.string.costs_paid_us, false to Res.string.costs_paid_them), wePaid) { wePaid = it }
            Text(stringResource(Res.string.costs_split), style = MaterialTheme.typography.labelLarge)
            Chips(
                listOf(HALF to Res.string.costs_split_half, ALL to Res.string.costs_split_all, NONE to Res.string.costs_split_none),
                share,
            ) {
                share = it
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ExpenseCategory.entries.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(categoryName(c)) },
                        colors = SelectionColors.filterChip(),
                    )
                }
            }
            if (amount != null) {
                val theirs = SharedCosts.otherShare(SharedExpense("", "", amount, date, "", otherSharePercent = share))
                val key = if (wePaid) Res.string.costs_preview_they else Res.string.costs_preview_we
                Text(stringResource(key, SharedCosts.format(theirs, decimal)), style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { amount?.let { onSave(title, it, date, wePaid, share, category) } },
                enabled = title.isNotBlank() && amount != null,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(Res.string.action_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, StringResource>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = { Text(stringResource(label)) },
                colors = SelectionColors.filterChip(),
            )
        }
    }
}

@Composable
private fun categoryName(c: ExpenseCategory): String = stringResource(
    when (c) {
        ExpenseCategory.Clothes -> Res.string.costs_category_clothes
        ExpenseCategory.Activities -> Res.string.costs_category_activities
        ExpenseCategory.School -> Res.string.costs_category_school
        ExpenseCategory.Health -> Res.string.costs_category_health
        ExpenseCategory.Other -> Res.string.costs_category_other
    },
)

private const val HALF = 50
private const val ALL = 100
private const val NONE = 0
private const val MAX_TITLE = 200

package se.pusslet.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.pusslet.core.model.PantryItem
import se.pusslet.core.model.Role
import se.pusslet.core.quickadd.ParsedItem
import se.pusslet.core.shopping.PantryGroup
import se.pusslet.core.shopping.PantryRules
import se.pusslet.core.shopping.ProductCategories
import se.pusslet.core.shopping.ShoppingInput
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.ShoppingRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sources.ProductInfo
import se.pusslet.data.sources.ProductLookup
import kotlin.time.Clock

data class PantryUiState(
    val loading: Boolean = true,
    /** Skafferi, Kyl and Frys, each with what is at home first and "slut" last; empty places are left out. */
    val groups: List<PantryGroup> = emptyList(),
    val usePantry: Boolean = false,
    /** Parents keep the pantry; helpers only read it (Firestore rules). */
    val canManage: Boolean = true,
    /** For "Går ut snart" (from the injected clock and time zone). */
    val today: LocalDate? = null,
) {
    val items: List<PantryItem> get() = groups.flatMap { it.items }
    val isEmpty: Boolean get() = groups.isEmpty()
}

/** One-off feedback on "Det vi har hemma", shown as a snackbar or dialog. */
sealed interface PantryMessage {
    /** "Mjölk borttagen · Ångra": [item] as it was, so "Ångra" puts it back exactly. */
    data class Removed(val item: PantryItem) : PantryMessage

    /** A scanned product, now at home ("Tillagt: Arla Mellanmjölk"). */
    data class Added(val name: String) : PantryMessage

    /** Open Food Facts does not know the code (or we are offline): ask for a name. */
    data class UnknownProduct(val barcode: String) : PantryMessage

    data object Failed : PantryMessage
}

/**
 * "Det vi har hemma" (see [PantryItem] for "Slut", "Finns hemma" and known amounts). Only the family changes it: making
 * or ticking off the shopping list never does.
 */
class PantryViewModel(
    private val shopping: ShoppingRepository,
    private val family: FamilyRepository,
    sessions: SessionProvider,
    private val ids: IdGenerator,
    private val products: ProductLookup,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val log = Logger.withTag("Pantry")
    private val messageChannel = Channel<PantryMessage>(Channel.BUFFERED)
    val messages: Flow<PantryMessage> = messageChannel.receiveAsFlow()

    val state: StateFlow<PantryUiState> = combine(shopping.pantry, family.settings, sessions.session) { items, settings, session ->
        PantryUiState(
            loading = false,
            groups = PantryRules.grouped(items),
            usePantry = settings.usePantry,
            canManage = session.role == Role.Parent,
            today = PantryRules.today(clock, zone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PantryUiState())

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: IllegalStateException) {
                if (e is CancellationException) throw e
                // E.g. no family selected yet; tell the user instead of crashing.
                log.w { "Pantry action failed: ${e::class.simpleName}" }
                messageChannel.send(PantryMessage.Failed)
            }
        }
    }

    /** "1 kg mjöl, ris": something already noted gets the new amount (and is at home again) instead of a second line. */
    fun add(text: String) = launchSafely {
        val parsed = ShoppingInput.parse(text)
        if (parsed.isEmpty()) return@launchSafely
        val overrides = shopping.aisleOverrides.first()
        val existing = shopping.pantry.first().toMutableList()
        parsed.forEach { item ->
            val saved = PantryRules.add(existing, item, ids::newId, overrides)
            shopping.savePantry(saved)
            existing.removeAll { it.id == saved.id }
            existing += saved
        }
    }

    /** From the editor: name, place, amount (empty = "Finns hemma") and best-before date. */
    fun save(item: PantryItem) = launchSafely {
        if (item.name.isBlank()) return@launchSafely
        shopping.savePantry(item.copy(name = item.name.trim()))
    }

    /** "Slut" / "Finns igen": the item stays in the list either way. */
    fun setInStock(item: PantryItem, inStock: Boolean) = launchSafely {
        shopping.savePantry(item.copy(inStock = inStock))
    }

    fun remove(item: PantryItem) = launchSafely {
        shopping.deletePantry(item.id)
        messageChannel.send(PantryMessage.Removed(item))
    }

    /** "Ångra" after [remove]. */
    fun restore(item: PantryItem) = launchSafely { shopping.savePantry(item) }

    fun setUsePantry(enabled: Boolean) = launchSafely { family.saveSettings { it.copy(usePantry = enabled) } }

    /**
     * Result from the camera scanner (null = cancelled), looked up in Open Food Facts like on the shopping list. A package
     * scanned before is at home again; an unknown code asks for a name.
     */
    fun scanned(code: String?) = launchSafely {
        val barcode = code?.filter { it.isDigit() }.orEmpty()
        if (barcode.isEmpty()) return@launchSafely
        val existing = shopping.pantry.first()
        val known = existing.firstOrNull { it.barcode == barcode }
        if (known != null) {
            shopping.savePantry(known.copy(inStock = true))
            messageChannel.send(PantryMessage.Added(known.name))
            return@launchSafely
        }
        val product = products.product(barcode)
        val name = product?.let(::displayName)
        if (name == null) {
            messageChannel.send(PantryMessage.UnknownProduct(barcode))
            return@launchSafely
        }
        val location = ProductCategories.aisle(product.categories)?.let(PantryRules::location)
        val saved = PantryRules.add(existing, ParsedItem(name), ids::newId, shopping.aisleOverrides.first(), barcode, location)
        shopping.savePantry(saved)
        messageChannel.send(PantryMessage.Added(saved.name))
    }

    /** For a code Open Food Facts does not know: the user types the name, the code is remembered on the item. */
    fun addNamedProduct(barcode: String, name: String) = launchSafely {
        val parsed = ShoppingInput.parse(name).firstOrNull() ?: return@launchSafely
        val code = barcode.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
        val saved = PantryRules.add(shopping.pantry.first(), parsed, ids::newId, shopping.aisleOverrides.first(), code)
        shopping.savePantry(saved)
    }

    private fun displayName(p: ProductInfo): String? {
        val name = p.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val brand = p.brand?.trim()?.takeIf { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
        return listOfNotNull(brand, name).joinToString(" ")
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

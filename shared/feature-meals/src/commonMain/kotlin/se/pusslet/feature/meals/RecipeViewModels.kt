package se.pusslet.feature.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.pusslet.core.meals.RecipeCollectionRules
import se.pusslet.core.meals.RecipeScaler
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeCollection
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.nutrition.Food
import se.pusslet.core.nutrition.NutritionSummary
import se.pusslet.core.recipes.ImportWarning
import se.pusslet.core.recipes.RecipeText
import se.pusslet.core.shopping.ShoppingNames
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.MealRepository
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.usecase.AddOutcome
import se.pusslet.data.usecase.RecipeImportResult
import se.pusslet.data.usecase.RecipeImporter
import se.pusslet.data.usecase.RecipeNutrition
import se.pusslet.data.usecase.ShoppingAdder
import kotlin.time.Clock

// region library

/** A collection's filter chip: the collection and how many of its recipes still exist. */
data class CollectionChip(val collection: RecipeCollection, val count: Int)

data class RecipeLibraryState(
    val loading: Boolean = true,
    val recipes: List<Recipe> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val tags: Set<RecipeTag> = emptySet(),
    val canEdit: Boolean = true,
    /** The family's collections by name ("Kalas", "Matlådor"). */
    val collections: List<CollectionChip> = emptyList(),
    /** The collection filtered on, if any. */
    val collection: RecipeCollection? = null,
) {
    /** The chosen collection has no recipes (yet): its own empty state instead of "Inga recept matchar". */
    val collectionIsEmpty: Boolean get() = collection != null && collections.firstOrNull { it.collection.id == collection.id }?.count == 0
}

class RecipeLibraryViewModel(private val recipes: RecipeRepository, sessions: SessionProvider, private val ids: IdGenerator) : ViewModel() {
    private val query = MutableStateFlow("")
    private val tags = MutableStateFlow<Set<RecipeTag>>(emptySet())
    private val selected = MutableStateFlow<String?>(null)

    private data class Filters(val query: String, val tags: Set<RecipeTag>, val collectionId: String?)

    private val filters = combine(query, tags, selected) { q, t, c -> Filters(q, t, c) }

    val state: StateFlow<RecipeLibraryState> = combine(recipes.recipes, recipes.collections, filters, sessions.session) { all, cs, f, s ->
        // A collection deleted on another phone simply stops filtering.
        val collection = cs.firstOrNull { it.id == f.collectionId }
        RecipeLibraryState(
            loading = false,
            recipes = all.filter { (collection == null || it.id in collection) && matches(it, f.query, f.tags) }.sortedWith(favoritesFirst),
            total = all.size,
            query = f.query,
            tags = f.tags,
            canEdit = s.role == Role.Parent,
            collections = RecipeCollectionRules.sorted(cs).map { CollectionChip(it, RecipeCollectionRules.count(it, all)) },
            collection = collection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipeLibraryState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleTag(tag: RecipeTag) {
        tags.update { if (tag in it) it - tag else it + tag }
    }

    /** A tap on a collection's chip filters on it; a second tap shows every recipe again. */
    fun toggleCollection(id: String) {
        selected.update { if (it == id) null else id }
    }

    /** "Ny samling": false (nothing saved) when the name is blank or already used. The new collection is shown at once. */
    fun createCollection(name: String): Boolean {
        val clean = RecipeCollectionRules.cleanName(name) ?: return false
        if (RecipeCollectionRules.nameTaken(currentCollections(), clean)) return false
        val collection = RecipeCollection(ids.newId(), clean)
        selected.value = collection.id
        viewModelScope.launch { recipes.saveCollection(collection) }
        return true
    }

    /** "Byt namn": false when the name is blank or another collection has it. */
    fun renameCollection(id: String, name: String): Boolean {
        val clean = RecipeCollectionRules.cleanName(name) ?: return false
        val current = currentCollections()
        if (RecipeCollectionRules.nameTaken(current, clean, exceptId = id)) return false
        val collection = current.firstOrNull { it.id == id } ?: return false
        if (collection.name != clean) viewModelScope.launch { recipes.saveCollection(collection.copy(name = clean)) }
        return true
    }

    /** "Ta bort samlingen": the collection goes, its recipes stay. */
    fun deleteCollection(id: String) {
        if (selected.value == id) selected.value = null
        viewModelScope.launch { recipes.deleteCollection(id) }
    }

    private fun currentCollections(): List<RecipeCollection> = state.value.collections.map { it.collection }

    private fun matches(r: Recipe, q: String, t: Set<RecipeTag>): Boolean {
        if (!r.tags.containsAll(t - RecipeTag.Quick) || (RecipeTag.Quick in t && !r.isQuick)) return false
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return true
        return needle in r.title.lowercase() || r.ingredients.any { needle in it.name.lowercase() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val favoritesFirst = compareByDescending<Recipe> { it.favorite }.thenBy { it.title.lowercase() }
    }
}

// endregion

// region details

sealed interface NutritionState {
    data object Loading : NutritionState

    /** The food database has not been downloaded yet and the phone is offline. */
    data object Unavailable : NutritionState

    data class Ready(val summary: NutritionSummary) : NutritionState
}

/** Choosing which food in Livsmedelsverket's database an ingredient is. */
data class MatchingState(val ingredient: Ingredient, val candidates: List<Food>?, val current: Int?)

data class RecipeDetailsState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val recipe: Recipe? = null,
    val servings: Int = 4,
    val ingredients: List<Ingredient> = emptyList(),
    val nutrition: NutritionState = NutritionState.Loading,
    val mappings: Map<String, Int> = emptyMap(),
    val canEdit: Boolean = true,
    /** Every collection of the family, by name; [inCollection] tells which ones hold this recipe. */
    val collections: List<RecipeCollection> = emptyList(),
) {
    fun inCollection(collection: RecipeCollection): Boolean = recipe != null && recipe.id in collection
}

sealed interface RecipeMessage {
    data class AddedToList(val outcome: AddOutcome) : RecipeMessage

    /** "I matsedeln tor 15 okt · Ångra"; [undo] puts back what the day had before (null = nothing changed). */
    data class Planned(val date: LocalDate, val undo: PlanUndo? = null) : RecipeMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailsViewModel(
    private val recipes: RecipeRepository,
    private val nutrition: RecipeNutrition,
    private val adder: ShoppingAdder,
    private val meals: MealRepository,
    sessions: SessionProvider,
    private val clock: Clock,
    private val zone: TimeZone,
    private val ids: IdGenerator,
) : ViewModel() {
    private val log = Logger.withTag("Recipe")
    private val recipeId = MutableStateFlow<String?>(null)
    private val servings = MutableStateFlow<Int?>(null)
    private val matching = MutableStateFlow<MatchingState?>(null)
    private val messageChannel = Channel<RecipeMessage>(Channel.BUFFERED)
    private val removed = MutableStateFlow(false)
    private val history = MealPlanHistory(meals)

    val messages: Flow<RecipeMessage> = messageChannel.receiveAsFlow()
    val match: StateFlow<MatchingState?> = matching

    /** True once the recipe has been deleted here; the screen then closes. */
    val deleted: StateFlow<Boolean> = removed

    private val recipe: Flow<Recipe?> = recipeId.filterNotNull().flatMapLatest { recipes.recipe(it) }

    private val portions: Flow<Int?> = combine(recipe, servings) { r, s -> r?.let { s ?: it.servings } }

    /** "Försök igen" after [NutritionState.Unavailable]: recalculates for the current recipe and portions. */
    private val nutritionRetries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val nutritionState: Flow<NutritionState> = combine(recipe, portions, recipes.ingredientMappings) { r, p, _ -> r to p }
        .flatMapLatest { (r, p) ->
            flow {
                if (r == null || p == null) {
                    emit(NutritionState.Loading)
                    return@flow
                }
                emit(nutritionOf(r, p))
                nutritionRetries.collect {
                    emit(NutritionState.Loading)
                    emit(nutritionOf(r, p))
                }
            }
        }
        .onStart { emit(NutritionState.Loading) }

    private val mappingsAndCollections = combine(recipes.ingredientMappings, recipes.collections) { m, c -> m to c }

    val state: StateFlow<RecipeDetailsState> = combine(
        recipe,
        portions,
        nutritionState,
        mappingsAndCollections,
        sessions.session,
    ) { r, p, n, (m, collections), s ->
        val count = p ?: r?.servings ?: DEFAULT_SERVINGS
        RecipeDetailsState(
            loading = false,
            today = clock.now().toLocalDateTime(zone).date,
            recipe = r,
            servings = count,
            ingredients = r?.let { RecipeScaler.scale(it, count) }.orEmpty(),
            nutrition = n,
            mappings = m,
            canEdit = s.role == Role.Parent,
            collections = RecipeCollectionRules.sorted(collections),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecipeDetailsState())

    /** "Lägg i samling…": a tick puts the recipe in [collectionId], unticking takes it out (the recipe itself is untouched). */
    fun setInCollection(collectionId: String, inside: Boolean) {
        val r = state.value.recipe ?: return
        viewModelScope.launch { recipes.setInCollection(collectionId, r.id, inside) }
    }

    /** "Ny samling" in "Lägg i samling…": a new collection with this recipe in it; false when the name is blank or taken. */
    fun createCollection(name: String): Boolean {
        val r = state.value.recipe ?: return false
        val clean = RecipeCollectionRules.cleanName(name) ?: return false
        if (RecipeCollectionRules.nameTaken(state.value.collections, clean)) return false
        viewModelScope.launch { recipes.saveCollection(RecipeCollection(ids.newId(), clean).with(r.id)) }
        return true
    }

    private suspend fun nutritionOf(recipe: Recipe, portions: Int): NutritionState =
        nutrition.summary(recipe, portions)?.let { NutritionState.Ready(it) } ?: NutritionState.Unavailable

    fun load(id: String) {
        if (recipeId.value != id) {
            recipeId.value = id
            servings.value = null
        }
    }

    fun setServings(count: Int) {
        servings.value = count.coerceIn(1, MAX_SERVINGS)
    }

    /** Tries the food database again, e.g. once the phone is back online. */
    fun retryNutrition() {
        nutritionRetries.tryEmit(Unit)
    }

    fun toggleFavorite() {
        val r = state.value.recipe ?: return
        viewModelScope.launch { recipes.save(r.copy(favorite = !r.favorite)) }
    }

    fun delete() {
        val r = state.value.recipe ?: return
        viewModelScope.launch {
            recipes.delete(r.id)
            removed.value = true
        }
    }

    fun addToShoppingList() {
        val r = state.value.recipe ?: return
        val count = state.value.servings
        viewModelScope.launch { messageChannel.send(RecipeMessage.AddedToList(adder.addRecipe(r, count))) }
    }

    fun undoAdd(outcome: AddOutcome) {
        viewModelScope.launch { adder.undo(outcome) }
    }

    /** Puts this recipe on the meal plan for [date] (dinner), with the chosen number of portions. */
    fun planOn(date: LocalDate) {
        val r = state.value.recipe ?: return
        val count = state.value.servings
        val entry = MealEntry(date, recipeId = r.id, title = r.title, servings = count.takeIf { it != r.servings })
        viewModelScope.launch {
            val undo = history.record(listOf(date)) { meals.setEntry(entry) }
            messageChannel.send(RecipeMessage.Planned(date, undo))
        }
    }

    /** "Ångra" after [planOn]. */
    fun undoPlan(undo: PlanUndo) {
        viewModelScope.launch { history.undo(undo) }
    }

    fun startMatching(ingredient: Ingredient) {
        val current = ingredient.foodNumber ?: state.value.mappings[ingredient.name.trim().lowercase()]
        matching.value = MatchingState(ingredient, null, current)
        viewModelScope.launch {
            val candidates = nutrition.candidates(ingredient.name)
            matching.update { m -> if (m != null && m.ingredient == ingredient) m.copy(candidates = candidates) else m }
        }
    }

    fun confirmMatch(food: Food) {
        val m = matching.value ?: return
        matching.value = null
        viewModelScope.launch {
            nutrition.confirm(m.ingredient.name, food.number)
            log.i { "Ingredient match confirmed" }
        }
    }

    fun cancelMatching() {
        matching.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_SERVINGS = 4
        const val MAX_SERVINGS = 40
    }
}

// endregion

// region editor

/** What the editor shows; amounts and lists are kept as text so typing is never fought. */
data class RecipeForm(
    val title: String = "",
    val servings: String = "4",
    val prepMinutes: String = "",
    val totalMinutes: String = "",
    val ingredients: String = "",
    val steps: String = "",
    val tags: Set<RecipeTag> = emptySet(),
    val mainComponent: MainComponent? = null,
    val notes: String = "",
    val sourceUrl: String? = null,
    val sourceName: String? = null,
) {
    val servingsValue: Int? get() = servings.trim().toIntOrNull()?.takeIf { it in 1..MAX_FORM_SERVINGS }
    val valid: Boolean get() = title.isNotBlank() && servingsValue != null

    companion object {
        const val MAX_FORM_SERVINGS = 40

        fun of(r: Recipe) = RecipeForm(
            title = r.title,
            servings = r.servings.toString(),
            prepMinutes = r.prepMinutes?.toString().orEmpty(),
            totalMinutes = r.totalMinutes?.toString().orEmpty(),
            ingredients = RecipeText.formatIngredients(r.ingredients),
            steps = r.steps.joinToString("\n"),
            tags = r.tags,
            mainComponent = r.mainComponent,
            notes = r.notes,
            sourceUrl = r.sourceUrl,
            sourceName = r.sourceName,
        )
    }
}

data class RecipeEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: RecipeForm = RecipeForm(),
    /** Set after saving; the screen then opens or returns to the recipe. */
    val savedId: String? = null,
    /** The form as it was loaded, to tell whether Back would lose anything. */
    val loadedForm: RecipeForm = form,
) {
    /** Something was typed or pasted that is not saved yet: Back asks "Släng ändringarna?". */
    val hasChanges: Boolean get() = !loading && savedId == null && form != loadedForm
}

class RecipeEditorViewModel(private val recipes: RecipeRepository, private val ids: IdGenerator) : ViewModel() {
    private val mutable = MutableStateFlow(RecipeEditorState())
    val state: StateFlow<RecipeEditorState> = mutable
    private var original: Recipe? = null
    private var loadedKey: String? = null

    /** [sourceUrl] pre-fills a new recipe from a link that had no recipe data ("klistra in texten"). */
    fun load(recipeId: String?, sourceUrl: String? = null) {
        val key = "$recipeId|$sourceUrl"
        if (loadedKey == key) return
        loadedKey = key
        viewModelScope.launch {
            val r = recipeId?.let { recipes.get(it) }
            original = r
            val form = r?.let { RecipeForm.of(it) } ?: RecipeForm(sourceUrl = sourceUrl, sourceName = sourceUrl?.let(::hostOf))
            mutable.value = RecipeEditorState(loading = false, isNew = r == null, form = form, loadedForm = form)
        }
    }

    fun update(transform: (RecipeForm) -> RecipeForm) {
        mutable.update { it.copy(form = transform(it.form)) }
    }

    /** Fills the form from pasted text. Only empty fields are filled, so nothing typed is lost. */
    fun paste(text: String) {
        val split = RecipeText.split(text)
        update { f ->
            f.copy(
                title = f.title.ifBlank { split.title.orEmpty() },
                servings = if (f.servingsValue == null || f.servings == RecipeForm().servings) {
                    split.servings?.toString() ?: f.servings
                } else {
                    f.servings
                },
                ingredients = f.ingredients.ifBlank { RecipeText.formatIngredients(split.ingredients) },
                steps = f.steps.ifBlank { split.steps.joinToString("\n") },
            )
        }
    }

    fun save() {
        val s = mutable.value
        val f = s.form
        val servings = f.servingsValue ?: return
        if (!f.valid) return
        val ingredients = RecipeText.parseIngredients(f.ingredients)
        val base = original ?: Recipe(id = ids.newId(), title = f.title)
        val recipe = base.copy(
            title = f.title.trim(),
            servings = servings,
            ingredients = ingredients.map { ing -> keepMatch(ing, original?.ingredients.orEmpty()) },
            steps = RecipeText.parseSteps(f.steps),
            tags = f.tags,
            prepMinutes = f.prepMinutes.trim().toIntOrNull(),
            totalMinutes = f.totalMinutes.trim().toIntOrNull(),
            mainComponent = f.mainComponent,
            notes = f.notes.trim(),
            sourceUrl = f.sourceUrl,
            sourceName = f.sourceName,
        )
        viewModelScope.launch {
            recipes.save(recipe)
            mutable.update { it.copy(savedId = recipe.id) }
        }
    }

    /** Editing a line should not lose a confirmed food match for the same ingredient. */
    private fun keepMatch(ing: Ingredient, before: List<Ingredient>): Ingredient {
        val match = before.firstOrNull { ShoppingNames.canonical(it.name) == ShoppingNames.canonical(ing.name) }?.foodNumber
        return if (match != null) ing.copy(foodNumber = match) else ing
    }

    private fun hostOf(url: String): String? = Regex("""https?://(?:www\.)?([^/:?#]+)""").find(url)?.groupValues?.get(1)
}

// endregion

// region import

sealed interface ImportStatus {
    data object Idle : ImportStatus

    data object Loading : ImportStatus

    /** The page has no recipe data: offer to paste the text instead. */
    data class NoRecipeData(val url: String) : ImportStatus

    data object NotReachable : ImportStatus

    data object InvalidUrl : ImportStatus
}

/** A weekly menu or several links: the recipes saved, the collection they were gathered in and the links that gave none. */
data class ImportedBatch(val recipes: List<Recipe>, val collectionName: String?, val missed: Int)

data class RecipeImportState(
    val status: ImportStatus = ImportStatus.Idle,
    val savedId: String? = null,
    val warnings: Set<ImportWarning> = emptySet(),
    val batch: ImportedBatch? = null,
)

/**
 * "Importera från länk": reads schema.org Recipe data from a recipe page, a weekly menu's recipes or several links, and
 * saves them with the source kept. A weekly menu's recipes are gathered in a collection named after the menu.
 */
class RecipeImportViewModel(
    private val importer: RecipeImporter,
    private val recipes: RecipeRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    private val mutable = MutableStateFlow(RecipeImportState())
    val state: StateFlow<RecipeImportState> = mutable
    private var sharedImported = false

    /** A link shared to the app is imported once, not again when the user comes back from one of its recipes. */
    fun importShared(input: String) {
        if (sharedImported) return
        sharedImported = true
        import(input)
    }

    fun import(input: String) {
        if (mutable.value.status == ImportStatus.Loading) return
        mutable.value = RecipeImportState(ImportStatus.Loading)
        viewModelScope.launch {
            mutable.value = when (val result = importer.import(input)) {
                is RecipeImportResult.Found -> {
                    val id = recipes.saveImported(result.imported.recipe)
                    RecipeImportState(ImportStatus.Idle, savedId = id, warnings = result.imported.warnings)
                }
                is RecipeImportResult.Several -> {
                    val saved = result.recipes.map { it.recipe.copy(id = recipes.saveImported(it.recipe)) }
                    val collected = result.title?.let { recipes.collect(it, saved.map(Recipe::id), ids.newId()) }
                    val name = result.title?.takeIf { collected != null }?.let(RecipeCollectionRules::cleanName)
                    RecipeImportState(ImportStatus.Idle, batch = ImportedBatch(saved, name, result.missed))
                }
                RecipeImportResult.NoRecipeData -> RecipeImportState(ImportStatus.NoRecipeData(RecipeImporter.findUrl(input).orEmpty()))
                RecipeImportResult.NotReachable -> RecipeImportState(ImportStatus.NotReachable)
                RecipeImportResult.InvalidUrl -> RecipeImportState(ImportStatus.InvalidUrl)
            }
        }
    }

    fun reset() {
        mutable.value = RecipeImportState()
    }
}

// endregion

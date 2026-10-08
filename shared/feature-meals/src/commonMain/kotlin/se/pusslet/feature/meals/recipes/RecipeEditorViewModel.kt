package se.pusslet.feature.meals.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.recipes.ImportWarning
import se.pusslet.core.recipes.IngredientParser
import se.pusslet.core.recipes.RecipeTextSplitter
import se.pusslet.core.units.MeasureUnit
import se.pusslet.core.units.Units
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.RecipeRepository
import se.pusslet.data.usecase.RecipeImportResult
import se.pusslet.data.usecase.RecipeImporter

enum class EditorStage { Link, Paste, Form }

enum class ImportProblem { InvalidUrl, NotReachable, NoRecipeData }

/** The recipe as the user types it: ingredients and steps are plain text, one per line, parsed when saved. */
data class RecipeDraft(
    val id: String? = null,
    val title: String = "",
    val servings: Int = 4,
    val minutes: String = "",
    val ingredientsText: String = "",
    val stepsText: String = "",
    val tags: Set<RecipeTag> = emptySet(),
    val mainComponent: MainComponent? = null,
    val notes: String = "",
    val sourceUrl: String = "",
    val sourceName: String? = null,
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    /** Confirmed food matches survive editing as long as the ingredient keeps its name. */
    val foodNumbers: Map<String, Int> = emptyMap(),
) {
    /** What will be saved, so the editor can show how each line was understood. */
    val ingredients: List<Ingredient>
        get() {
            var group: String? = null
            return ingredientsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
                if (line.endsWith(':') && line.length < MAX_GROUP_CHARS) {
                    group = line.dropLast(1).trim()
                    null
                } else {
                    IngredientParser.parse(line, group).let { ing -> foodNumbers[ing.name]?.let { ing.copy(foodNumber = it) } ?: ing }
                }
            }
        }

    val steps: List<String> get() = stepsText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun toRecipe(id: String): Recipe = Recipe(
        id = id,
        title = title.trim(),
        servings = servings,
        ingredients = ingredients,
        steps = steps,
        tags = tags,
        totalMinutes = minutes.trim().toIntOrNull(),
        mainComponent = mainComponent,
        sourceUrl = sourceUrl.trim().ifEmpty { null },
        sourceName = sourceName,
        imageUrl = imageUrl,
        favorite = favorite,
        notes = notes.trim(),
    )

    companion object {
        private const val MAX_GROUP_CHARS = 60

        fun of(r: Recipe) = RecipeDraft(
            id = r.id,
            title = r.title,
            servings = r.servings,
            minutes = r.totalMinutes?.toString().orEmpty(),
            ingredientsText = ingredientLines(r.ingredients),
            stepsText = r.steps.joinToString("\n"),
            tags = r.tags,
            mainComponent = r.mainComponent,
            notes = r.notes,
            sourceUrl = r.sourceUrl.orEmpty(),
            sourceName = r.sourceName,
            imageUrl = r.imageUrl,
            favorite = r.favorite,
            foodNumbers = r.ingredients.mapNotNull { i -> i.foodNumber?.let { i.name to it } }.toMap(),
        )

        /** Back to editable text: "2 dl grädde", "1 gul lök, finhackad"; whole pieces without "st". */
        fun ingredientLines(ingredients: List<Ingredient>): String {
            var group: String? = null
            return buildList {
                for (i in ingredients) {
                    if (i.group != null && i.group != group) {
                        group = i.group
                        add("${i.group}:")
                    }
                    val amount = i.amount?.let { q ->
                        if (q.unit == MeasureUnit.St) Units.format(q.copy(unit = null)) else Units.format(q)
                    }
                    add(listOfNotNull(amount, i.name).joinToString(" ") + if (i.note.isNotBlank()) ", ${i.note}" else "")
                }
            }.joinToString("\n")
        }
    }
}

enum class RecipeError { NameRequired }

data class RecipeEditorState(
    val stage: EditorStage = EditorStage.Form,
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: RecipeDraft = RecipeDraft(),
    val url: String = "",
    val pasteText: String = "",
    val fetching: Boolean = false,
    val problem: ImportProblem? = null,
    val warnings: Set<ImportWarning> = emptySet(),
    val pasteResult: Pair<Int, Int>? = null,
    val errors: Set<RecipeError> = emptySet(),
    val saving: Boolean = false,
    val savedId: String? = null,
)

class RecipeEditorViewModel(
    private val recipes: RecipeRepository,
    private val importer: RecipeImporter,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(RecipeEditorState())
    val state: StateFlow<RecipeEditorState> = _state.asStateFlow()
    private var started = false

    /** [link] starts with the URL field (pre-filled from a shared link), [paste] with the text field. */
    fun start(recipeId: String?, link: Boolean, url: String?, paste: Boolean) {
        if (started) return
        started = true
        when {
            recipeId != null -> viewModelScope.launch {
                val r = recipes.get(recipeId)
                _state.value = RecipeEditorState(loading = false, isNew = r == null, draft = r?.let { RecipeDraft.of(it) } ?: RecipeDraft())
            }
            link || url != null -> {
                _state.value = RecipeEditorState(stage = EditorStage.Link, loading = false, url = url.orEmpty())
                if (url != null && RecipeImporter.findUrl(url) != null) fetch()
            }
            paste -> _state.value = RecipeEditorState(stage = EditorStage.Paste, loading = false)
            else -> _state.value = RecipeEditorState(loading = false)
        }
    }

    fun edit(transform: (RecipeDraft) -> RecipeDraft) = _state.update { it.copy(draft = transform(it.draft), errors = emptySet()) }

    fun toggleTag(tag: RecipeTag) = edit { d -> d.copy(tags = if (tag in d.tags) d.tags - tag else d.tags + tag) }

    fun setUrl(url: String) = _state.update { it.copy(url = url, problem = null) }

    fun setPasteText(text: String) = _state.update { it.copy(pasteText = text) }

    fun showPaste() = _state.update { it.copy(stage = EditorStage.Paste, problem = null) }

    fun fetch() {
        val url = _state.value.url
        _state.update { it.copy(fetching = true, problem = null) }
        viewModelScope.launch {
            when (val result = importer.import(url)) {
                is RecipeImportResult.Found -> _state.update {
                    it.copy(
                        stage = EditorStage.Form,
                        fetching = false,
                        draft = RecipeDraft.of(result.imported.recipe).copy(id = null),
                        warnings = result.imported.warnings,
                    )
                }
                RecipeImportResult.InvalidUrl -> _state.update { it.copy(fetching = false, problem = ImportProblem.InvalidUrl) }
                RecipeImportResult.NotReachable -> _state.update { it.copy(fetching = false, problem = ImportProblem.NotReachable) }
                RecipeImportResult.NoRecipeData -> _state.update {
                    // Keep the link as the source; the user pastes the text instead.
                    it.copy(
                        fetching = false,
                        problem = ImportProblem.NoRecipeData,
                        draft = it.draft.copy(sourceUrl = RecipeImporter.findUrl(url).orEmpty()),
                    )
                }
            }
        }
    }

    /** Rule-based split of pasted text into title, ingredients and steps; the user checks the form afterwards. */
    fun split() {
        val split = RecipeTextSplitter.split(_state.value.pasteText)
        _state.update {
            it.copy(
                stage = EditorStage.Form,
                draft = it.draft.copy(
                    title = it.draft.title.ifBlank { split.title },
                    servings = split.servings ?: it.draft.servings,
                    ingredientsText = RecipeDraft.ingredientLines(split.ingredients),
                    stepsText = split.steps.joinToString("\n"),
                ),
                pasteResult = split.ingredients.size to split.steps.size,
            )
        }
    }

    fun save() {
        val s = _state.value
        if (s.draft.title.isBlank()) {
            _state.update { it.copy(errors = setOf(RecipeError.NameRequired)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = s.draft.id ?: ids.newId()
            recipes.save(s.draft.toRecipe(id))
            _state.update { it.copy(saving = false, savedId = id) }
        }
    }
}

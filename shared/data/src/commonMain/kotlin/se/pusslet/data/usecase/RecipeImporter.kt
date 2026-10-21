package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import se.pusslet.core.recipes.ImportedRecipe
import se.pusslet.core.recipes.RecipeJsonLd
import se.pusslet.core.recipes.RecipeLinks
import se.pusslet.core.util.IdGenerator

sealed interface RecipeImportResult {
    data class Found(val imported: ImportedRecipe) : RecipeImportResult

    /**
     * Several recipes: the ones on a weekly menu (named [title]) or from several pasted links. [missed] counts the links
     * that gave no recipe.
     */
    data class Several(val title: String?, val recipes: List<ImportedRecipe>, val missed: Int) : RecipeImportResult

    /** The page loaded but has no schema.org Recipe data: the user can enter it by hand. */
    data object NoRecipeData : RecipeImportResult

    data object NotReachable : RecipeImportResult

    data object InvalidUrl : RecipeImportResult
}

/**
 * Imports recipes from links the user pasted or shared: one recipe page, a weekly menu (its recipes, ADR-038) or several
 * links at once. Fetches only those pages, one at a time (no crawling, no images), and reads their structured data;
 * the source is kept on each recipe and shown with it.
 */
class RecipeImporter(private val http: HttpClient, private val ids: IdGenerator) {
    private val log = Logger.withTag("RecipeImport")

    suspend fun import(input: String): RecipeImportResult {
        val urls = findUrls(input)
        if (urls.isEmpty()) return RecipeImportResult.InvalidUrl
        if (urls.size > 1) return several(title = null, urls)
        val url = urls.single()
        val html = fetch(url) ?: return RecipeImportResult.NotReachable
        RecipeJsonLd.extract(html, url)?.let { return RecipeImportResult.Found(it.withId(ids.newId())) }
        val menu = RecipeLinks.extract(html, url) ?: return RecipeImportResult.NoRecipeData
        return several(menu.title, menu.links)
    }

    /** At most [RecipeLinks.MAX_LINKS] pages; the rest count as missed. */
    private suspend fun several(title: String?, urls: List<String>): RecipeImportResult {
        val results = urls.take(RecipeLinks.MAX_LINKS).map { recipeAt(it) }
        val found = results.filterIsInstance<RecipeImportResult.Found>().map { it.imported }
        return when {
            found.isNotEmpty() -> RecipeImportResult.Several(title, found, missed = urls.size - found.size)
            RecipeImportResult.NotReachable in results -> RecipeImportResult.NotReachable
            else -> RecipeImportResult.NoRecipeData
        }
    }

    private suspend fun recipeAt(url: String): RecipeImportResult {
        val html = fetch(url) ?: return RecipeImportResult.NotReachable
        return RecipeJsonLd.extract(html, url)?.let { RecipeImportResult.Found(it.withId(ids.newId())) } ?: RecipeImportResult.NoRecipeData
    }

    /** The page's HTML, or null when it can't be reached. */
    private suspend fun fetch(url: String): String? = try {
        val response = http.get(url) { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }
        if (response.status.isSuccess()) response.bodyAsText().take(MAX_PAGE_CHARS) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.i { "Recipe page not reachable (${e::class.simpleName})" }
        null
    }

    private fun ImportedRecipe.withId(id: String) = copy(recipe = recipe.copy(id = id))

    companion object {
        private const val MAX_PAGE_CHARS = 3_000_000
        private val urlPattern = Regex("""https?://[^\s<>"']+""")

        /** The first web link in a pasted or shared text ("Testa den här! https://…"). */
        fun findUrl(text: String): String? = findUrls(text).firstOrNull()

        /** Every web link in the text, in order and without repeats (a few recipes pasted from a note). */
        fun findUrls(text: String): List<String> =
            urlPattern.findAll(text).map { it.value.trimEnd('.', ',', ')', '!') }.distinct().toList()
    }
}

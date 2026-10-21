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
import se.pusslet.core.util.IdGenerator

sealed interface RecipeImportResult {
    data class Found(val imported: ImportedRecipe) : RecipeImportResult

    /** The page loaded but has no schema.org Recipe data: the user can enter it by hand. */
    data object NoRecipeData : RecipeImportResult

    data object NotReachable : RecipeImportResult

    data object InvalidUrl : RecipeImportResult
}

/** One page of several pasted links: its recipes (a week menu can carry more than one), or why there are none. */
sealed interface PageImportResult {
    data class Found(val recipes: List<ImportedRecipe>) : PageImportResult

    data object NoRecipeData : PageImportResult

    data object NotReachable : PageImportResult
}

/**
 * Imports one recipe from a link the user pasted or shared. Fetches only that page (no crawling, no images) and reads
 * its structured data; the source is kept on the recipe and shown with it.
 */
class RecipeImporter(private val http: HttpClient, private val ids: IdGenerator) {
    private val log = Logger.withTag("RecipeImport")

    suspend fun import(input: String): RecipeImportResult {
        val url = findUrl(input) ?: return RecipeImportResult.InvalidUrl
        val html = page(url) ?: return RecipeImportResult.NotReachable
        return RecipeJsonLd.extract(html, url, ids.newId())?.let { RecipeImportResult.Found(it) } ?: RecipeImportResult.NoRecipeData
    }

    /** Every recipe on the page at [url], for several pasted links; only that page is fetched. */
    suspend fun importPage(url: String): PageImportResult {
        val html = page(url) ?: return PageImportResult.NotReachable
        val recipes = RecipeJsonLd.extractAll(html, url, ids::newId)
        return if (recipes.isEmpty()) PageImportResult.NoRecipeData else PageImportResult.Found(recipes)
    }

    /** The page's HTML, or null when it cannot be fetched (logged without the address, which a user typed). */
    private suspend fun page(url: String): String? = try {
        val response = http.get(url) { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }
        if (response.status.isSuccess()) response.bodyAsText().take(MAX_PAGE_CHARS) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.i { "Recipe page not reachable (${e::class.simpleName})" }
        null
    }

    companion object {
        private const val MAX_PAGE_CHARS = 3_000_000

        /** More links than this in one paste are left out, so a pasted page of links cannot start hundreds of fetches. */
        const val MAX_LINKS = 30
        private val urlPattern = Regex("""https?://[^\s<>"']+""")

        /** The first web link in a pasted or shared text ("Testa den här! https://…"). */
        fun findUrl(text: String): String? = findUrls(text).firstOrNull()

        /** Every distinct web link in a pasted text, in order, at most [MAX_LINKS]. */
        fun findUrls(text: String): List<String> = urlPattern.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', '!') }
            .distinct()
            .take(MAX_LINKS)
            .toList()
    }
}

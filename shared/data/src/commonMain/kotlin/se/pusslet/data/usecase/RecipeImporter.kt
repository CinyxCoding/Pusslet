package se.pusslet.data.usecase

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import se.pusslet.core.recipes.ImportedRecipe
import se.pusslet.core.recipes.RecipeJsonLd
import se.pusslet.core.recipes.RecipeLinks
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.net.bodyAsTextAtMost

sealed interface RecipeImportResult {
    data class Found(val imported: ImportedRecipe) : RecipeImportResult

    /** Several recipes with their data on the one page (a menu or a collection), named [title] when the page has a name. */
    data class Several(val title: String?, val recipes: List<ImportedRecipe>) : RecipeImportResult

    /** A weekly menu that only links to its recipes (ADR-038); they are fetched one at a time with [RecipeImporter.importPage]. */
    data class Menu(val title: String?, val links: List<String>) : RecipeImportResult

    /** The page loaded but has no schema.org Recipe data: the user can enter it by hand. */
    data object NoRecipeData : RecipeImportResult

    data object NotReachable : RecipeImportResult

    data object InvalidUrl : RecipeImportResult
}

/** One page of several links: its recipes (a week menu can carry more than one), a menu's links, or why there are none. */
sealed interface PageImportResult {
    /** [title] names a page with several recipes, for the collection they are gathered in. */
    data class Found(val recipes: List<ImportedRecipe>, val title: String? = null) : PageImportResult

    /** A weekly menu that links to its recipes; the list follows them, one page at a time. */
    data class Menu(val title: String?, val links: List<String>) : PageImportResult

    data object NoRecipeData : PageImportResult

    data object NotReachable : PageImportResult
}

/**
 * Imports recipes from links the user pasted or shared: one recipe page, a weekly menu (its recipes, ADR-038) or several
 * links at once. Fetches only those pages, one at a time (no crawling, no images), and reads their structured data;
 * the source is kept on each recipe and shown with it.
 */
class RecipeImporter(private val http: HttpClient, private val ids: IdGenerator) {
    private val log = Logger.withTag("RecipeImport")

    /** The first link in [input]: a recipe, the recipes on the page, or the links of a weekly menu. */
    suspend fun import(input: String): RecipeImportResult {
        val url = findUrl(input) ?: return RecipeImportResult.InvalidUrl
        val html = page(url) ?: return RecipeImportResult.NotReachable
        val all = RecipeJsonLd.extractAll(html, url)
        if (all.size > 1) return RecipeImportResult.Several(RecipeLinks.titleOf(html), all.map { it.withId(ids.newId()) })
        RecipeJsonLd.extract(html, url)?.let { return RecipeImportResult.Found(it.withId(ids.newId())) }
        val menu = RecipeLinks.extract(html, url) ?: return RecipeImportResult.NoRecipeData
        return RecipeImportResult.Menu(menu.title, menu.links)
    }

    /** Every recipe on the page at [url], or the links of a weekly menu on it; only that page is fetched. */
    suspend fun importPage(url: String): PageImportResult {
        val html = page(url) ?: return PageImportResult.NotReachable
        val recipes = RecipeJsonLd.extractAll(html, url, ids::newId)
        if (recipes.isNotEmpty()) return PageImportResult.Found(recipes, title = RecipeLinks.titleOf(html).takeIf { recipes.size > 1 })
        val menu = RecipeLinks.extract(html, url) ?: return PageImportResult.NoRecipeData
        return PageImportResult.Menu(menu.title, menu.links)
    }

    /** The page's HTML, or null when it cannot be fetched (logged without the address, which a user typed). */
    private suspend fun page(url: String): String? = try {
        http.prepareGet(url) { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }.execute { response ->
            if (response.status.isSuccess()) response.bodyAsTextAtMost(MAX_PAGE_BYTES).take(MAX_PAGE_CHARS) else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.i { "Recipe page not reachable (${e::class.simpleName})" }
        null
    }

    private fun ImportedRecipe.withId(id: String) = copy(recipe = recipe.copy(id = id))

    companion object {
        private const val MAX_PAGE_CHARS = 3_000_000

        /** Recipe pages are well under this; a larger answer is not read at all (the link can come from anywhere). */
        private const val MAX_PAGE_BYTES = 8L * 1024 * 1024

        /**
         * More links than this in one paste are left out, and one import never reads more pages than this (menus included),
         * so a pasted page of links cannot start hundreds of fetches.
         */
        const val MAX_LINKS = 30
        private val urlPattern = Regex("""https?://[^\s<>"']+""")

        /** The first web link in a pasted or shared text ("Testa den här! https://…"). */
        fun findUrl(text: String): String? = findUrls(text).firstOrNull()

        /** Every distinct web link in a pasted text, in order, at most [MAX_LINKS] (a few recipes pasted from a note). */
        fun findUrls(text: String): List<String> = urlPattern.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', '!') }
            .distinct()
            .take(MAX_LINKS)
            .toList()
    }
}

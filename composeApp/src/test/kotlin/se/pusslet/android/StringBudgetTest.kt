package se.pusslet.android

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * The text budget from the writing rules (docs/DESIGN.md › Ton i text, ADR-024), in Swedish and English: "!" only where
 * something is finished, never "add with +" (the + is visible), labels of at most three words and short strings on the
 * tab roots. Curated keys that are removed later simply drop out of the length check.
 */
class StringBudgetTest {
    private val dir = File("../shared/ui/src/commonMain/composeResources")

    private fun load(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "$folder/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    /** Characters a reader sees, not counting what the placeholders are replaced with. */
    private fun visibleLength(value: String) = placeholder.replace(value, "").trim().length

    private fun wordCount(value: String) = value.trim().split(whitespace).count { it.isNotEmpty() }

    @Test
    fun exclamationMarksOnlyWhereSomethingIsDone() {
        for (folder in folders) {
            val bad = load(folder).filter { (key, value) -> '!' in value && key !in exclamationAllowed }.keys
            assertEquals(emptySet(), bad, "$folder uses \"!\" outside $exclamationAllowed")
        }
    }

    @Test
    fun neverSayAddWithPlus() {
        for (folder in folders) {
            val bad = load(folder).filter { (key, value) -> addWithPlus.containsMatchIn(value) && key !in addWithPlusAllowed }.keys
            assertEquals(emptySet(), bad, "$folder says \"med +\" / \"with +\" outside $addWithPlusAllowed")
        }
    }

    @Test
    fun labelsHaveAtMostThreeWords() {
        for (folder in folders) {
            val tooLong = load(folder).filter { (key, value) ->
                (labelPrefixes.any { key.startsWith(it) } || key in sectionTitles) && wordCount(value) > MAX_LABEL_WORDS
            }
            assertEquals(emptyMap(), tooLong, "$folder has labels with more than $MAX_LABEL_WORDS words")
        }
    }

    @Test
    fun mainScreenStringsAreShort() {
        for (folder in folders) {
            val strings = load(folder)
            val tooLong = strings.filter { (key, value) -> key in mainScreen && visibleLength(value) > MAX_MAIN_SCREEN_CHARS }
            assertEquals(emptyMap(), tooLong, "$folder has main-screen strings over $MAX_MAIN_SCREEN_CHARS characters")
            val longProblems = strings.filter { (key, value) -> key in problems && visibleLength(value) > MAX_PROBLEM_CHARS }
            assertEquals(emptyMap(), longProblems, "$folder has problems over $MAX_PROBLEM_CHARS characters")
        }
    }
}

private const val MAX_LABEL_WORDS = 3
private const val MAX_MAIN_SCREEN_CHARS = 40
private const val MAX_PROBLEM_CHARS = 60

private val folders = listOf("values", "values-en")
private val placeholder = Regex("""%(\d+\$)?[sd]""")
private val whitespace = Regex("""\s+""")
private val addWithPlus = Regex("""\b(med|with)\s+\+""", RegexOption.IGNORE_CASE)

/** Done states may cheer; receipts, errors and everything else may not. */
private val exclamationAllowed = setOf("shopping_all_done", "store_mode_all_done_title", "onboarding_done_title")

/** The done screen of the onboarding is the only place where no + is visible yet. */
private val addWithPlusAllowed = setOf("onboarding_done_body")

private val labelPrefixes = listOf("nav_", "layer_", "reason_", "action_")
private val sectionTitles = setOf(
    "today_timeline_title",
    "today_packing_title",
    "today_meals_title",
    "today_tasks_title",
    "today_upcoming_title",
)

/** Strings shown on the tab roots (Idag, Kalender, Mat, Handla, Mer) and on the shell. */
private val mainScreen = setOf(
    // Idag
    "today_weather_line",
    "today_weather_tomorrow",
    "today_weather_missing",
    "today_weather_unavailable",
    "today_weather_stale",
    "today_timeline_title",
    "today_packing_title",
    "today_meals_title",
    "today_tasks_title",
    "today_upcoming_title",
    "today_lunch",
    "today_dinner",
    "today_plan_dinner",
    "today_clash",
    "today_quiet",
    "today_custody",
    "today_custody_handover",
    "today_custody_next",
    "today_handoff_nobody",
    "today_dropoff",
    "today_pickup",
    "card_party_gift",
    "card_study_day",
    "card_squeeze_day",
    "card_action_take",
    "card_action_remind",
    "card_action_open",
    // Kalender
    "calendar_layers",
    "calendar_empty_week",
    "calendar_new_event",
    "calendar_view_week",
    "calendar_view_month",
    // Mat
    "meals_section_week",
    "meals_section_recipes",
    "meals_plan_short",
    "meals_clash",
    "meals_add_lunch",
    "meals_busy_evening",
    "meals_shop_for_week",
    "meals_leftovers_from",
    "week_nutrition_title",
    "nutrition_unavailable",
    "nutrition_source",
    "recipe_search",
    "recipe_empty_title",
    // Handla
    "shopping_left_count",
    "shopping_shopper_now",
    "shopping_someone_shopping",
    "shopping_add_hint",
    "shopping_empty_title",
    "shopping_empty_body",
    "shopping_already_on_list",
    "shopping_from_meals",
    "shopping_store_mode",
    "shopping_in_cart_count",
    "shopping_clear_checked",
    "shopping_suggestions_title",
    "shopping_nothing_left",
    "store_mode_finish",
    "store_mode_all_done_title",
    // Skalet och Mer
    "quick_add",
    "quickadd_title",
    "quickadd_saved",
    "more_family",
    "more_tasks",
    "more_settings",
    "more_account",
    "more_about_data",
    "more_privacy",
)

/** Problems are whole sentences ("Ingen kan hämta Ella kl 15:45") and get a little more room. */
private val problems = setOf(
    "card_nobody_pickup",
    "card_nobody_dropoff",
    "card_busy_pickup",
    "card_busy_dropoff",
    "card_unassigned_pickup",
    "card_unassigned_dropoff",
    "card_double_booked",
)

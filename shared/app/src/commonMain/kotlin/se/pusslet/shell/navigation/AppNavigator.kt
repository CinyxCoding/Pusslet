package se.pusslet.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.json.Json

/**
 * Top-level navigation state: one back stack per bottom-bar tab (so switching tabs keeps your place),
 * shown on top of the Today stack so that "back" from any tab root returns to Today and then exits.
 * Works with predictive back because NavDisplay always receives the full visible stack.
 */
class AppNavigator internal constructor(
    initialTab: TopLevelTab,
    initialStacks: Map<TopLevelTab, List<Route>>,
) {
    var currentTab: TopLevelTab by mutableStateOf(initialTab)
        private set

    private val stacks: Map<TopLevelTab, SnapshotStateList<Route>> = TopLevelTab.entries.associateWith { tab ->
        mutableStateListOf<Route>().apply { addAll(initialStacks[tab]?.takeIf { it.isNotEmpty() } ?: listOf(tab.root)) }
    }

    /** The list NavDisplay renders. */
    val backStack: List<Route>
        get() = if (currentTab == TopLevelTab.Today) {
            stack(TopLevelTab.Today)
        } else {
            stack(TopLevelTab.Today) + stack(currentTab)
        }

    fun selectTab(tab: TopLevelTab) {
        if (tab == currentTab) {
            // Re-selecting a tab pops it to its root, like most Android apps.
            val stack = stack(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            currentTab = tab
        }
    }

    fun navigate(route: Route) {
        val tab = TopLevelTab.entries.firstOrNull { it.root == route }
        if (tab != null) {
            selectTab(tab)
        } else {
            stack(currentTab).add(route)
        }
    }

    /**
     * Opens what a tapped notification points at. A tab's root shows that tab from its root, not the screen last left open
     * there (Morgonkollen opens Idag). A screen already on top is not stacked again: when Android has restored the app
     * after closing it in the background, the tap arrives on top of the screen that was open.
     */
    fun open(route: Route) {
        val tab = TopLevelTab.entries.firstOrNull { it.root == route }
        if (tab != null) {
            currentTab = tab
            val stack = stack(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else if (backStack.lastOrNull() != route) {
            stack(currentTab).add(route)
        }
    }

    fun goBack() {
        val stack = stack(currentTab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            currentTab != TopLevelTab.Today -> currentTab = TopLevelTab.Today
            else -> Unit // Root of Today: the system handles back (leaves the app).
        }
    }

    /** Back to a fresh Today, e.g. after leaving a family on this device. */
    fun reset() {
        stacks.forEach { (tab, stack) ->
            stack.clear()
            stack.add(tab.root)
        }
        currentTab = TopLevelTab.Today
    }

    /** True when the visible screen is a tab root (bottom bar and quick add are shown). */
    val atTabRoot: Boolean get() = backStack.lastOrNull() in TopLevelTab.entries.map { it.root }

    private fun stack(tab: TopLevelTab): SnapshotStateList<Route> = requireNotNull(stacks[tab])

    internal fun snapshot(): String = json.encodeToString(
        SavedNavigation.serializer(),
        SavedNavigation(currentTab, TopLevelTab.entries.associateWith { stack(it).toList() }),
    )

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun restore(saved: String): AppNavigator? = runCatching {
            val state = json.decodeFromString(SavedNavigation.serializer(), saved)
            AppNavigator(state.tab, state.stacks)
        }.getOrNull()
    }
}

@kotlinx.serialization.Serializable
internal data class SavedNavigation(
    val tab: TopLevelTab,
    val stacks: Map<TopLevelTab, List<Route>>,
)

@Composable
fun rememberAppNavigator(): AppNavigator = rememberSaveable(
    saver = Saver(
        save = { it.snapshot() },
        restore = { AppNavigator.restore(it) },
    ),
) {
    AppNavigator(TopLevelTab.Today, emptyMap())
}

package se.pusslet.shell.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Navigation of the helper view (UX plan Steg 3): one stack with [Route.HelperHome] at the bottom and sub-pages with a top
 * bar and Back on top of it. There are no tabs, so `AppNavigator` is not used. Back from the home screen is left to the
 * system (it leaves the app).
 */
class HelperNavigator internal constructor(initial: List<Route>) {
    private val stack: SnapshotStateList<Route> = mutableStateListOf<Route>().apply {
        addAll(initial.takeIf { it.firstOrNull() == Route.HelperHome } ?: listOf(Route.HelperHome))
    }

    /** The list NavDisplay renders. */
    val backStack: List<Route> get() = stack

    val atRoot: Boolean get() = stack.size == 1

    /** Opens a sub-page; the home route goes back to the home screen instead of stacking it twice. */
    fun navigate(route: Route) {
        if (route == Route.HelperHome) reset() else stack.add(route)
    }

    fun goBack() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Back to the home screen, e.g. after leaving a family on this device. */
    fun reset() {
        stack.clear()
        stack.add(Route.HelperHome)
    }

    internal fun snapshot(): String = json.encodeToString(serializer, stack.toList())

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Route.serializer())
        private val log = Logger.withTag("HelperNavigator")

        /** A stack saved by an older version may not decode; the home screen is then the place to start. */
        fun restore(saved: String): HelperNavigator = runCatching { HelperNavigator(json.decodeFromString(serializer, saved)) }
            .getOrElse {
                log.w { "Saved helper navigation could not be read; starting on the home screen" }
                HelperNavigator(emptyList())
            }
    }
}

@Composable
fun rememberHelperNavigator(): HelperNavigator = rememberSaveable(
    saver = Saver(
        save = { it.snapshot() },
        restore = { HelperNavigator.restore(it) },
    ),
) {
    HelperNavigator(emptyList())
}

package se.pusslet.data.sync

import kotlinx.serialization.json.Json
import se.pusslet.core.custody.SharedHome
import se.pusslet.core.model.CustodySchedule
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.Family
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.HandoffRequest
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.PackingChecks
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.WishItem

/**
 * Where a new document goes when the family shares spaces with other homes (ADR-030): the space whose children it is
 * about ([SharedHome]), otherwise nowhere special (the family). A new child is always the family's own.
 */
internal class SpacePlacement(private val local: SyncLocalStore, private val json: Json) {
    suspend fun spaceFor(home: String, spaces: List<String>, spec: CollectionSpec, value: Any?): String? {
        if (spaces.isEmpty() || spec.spaces != SpaceSharing.Merged || value == null) return null
        val childrenBySpace = spaces.associateWith { ids(it, Collections.Children) }
        val everyChild = ids(home, Collections.Children) + childrenBySpace.values.flatten()
        return spaces.firstOrNull { space ->
            val shared = childrenBySpace.getValue(space)
            shared.isNotEmpty() && shares(SharedHome(shared, everyChild), space, value)
        }
    }

    private suspend fun shares(rules: SharedHome, space: String, value: Any): Boolean = when (value) {
        is Event -> rules.shares(value)
        is FamilyTask -> rules.shares(value)
        is PackingRule -> rules.shares(value)
        is PackingChecks -> rules.shares(value)
        is CustodySchedule -> rules.shares(value)
        is WishItem -> rules.shares(value)
        is ManualLunchWeek -> rules.shares(value)
        is DietProfile -> rules.shares(value, shareDiets = spaceDocument(space)?.shareDiets == true)
        is HandoffRequest -> rules.shares(value, sharedEventIds = ids(space, Collections.Events))
        else -> false
    }

    private suspend fun ids(root: String, spec: CollectionSpec): Set<String> = local.list(root, spec.key).map { it.docId }.toSet()

    private suspend fun spaceDocument(space: String): Family? = local.get(space, Collections.Family.key, space)
        ?.let { row -> runCatching { json.decodeFromString(Family.serializer(), row.json) }.getOrNull() }
}

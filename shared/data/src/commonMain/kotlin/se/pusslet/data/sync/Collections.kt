package se.pusslet.data.sync

import se.pusslet.core.model.Role

/** Where a local collection lives in Firestore, relative to `families/{familyId}`. */
sealed interface RemoteLocation {
    /** The family document itself (`families/{id}`); the local doc id is the family id. */
    data object FamilyRoot : RemoteLocation

    /** A direct subcollection, e.g. `events`. */
    data class Flat(val path: String) : RemoteLocation

    /** A subcollection under each parent document, e.g. `shoppingLists/{listId}/items`. */
    data class Nested(
        val parentCollection: String,
        val parentRemotePath: String,
        val parentField: String,
        val subPath: String,
    ) : RemoteLocation
}

/** Whether a collection exists in a space shared with the other home (ADR-030). */
enum class SpaceSharing {
    /** Only in the home. */
    None,

    /** Also in shared spaces, and the space's documents show up in the home's own lists (Ella's events). */
    Merged,

    /** Also in shared spaces, but read on their own (the space document, the other home's adults). */
    Separate,
}

/**
 * How one local collection syncs.
 * @param mapFields fields merged per key (per-key last-write-wins) instead of replaced as a whole.
 * @param caregiverFilterField caregivers only read documents where this boolean field is true.
 * @param parentsOnly caregivers do not read this collection at all.
 * @param localOnly kept on this phone only: never queued for upload and not part of [Collections.all].
 * @param spaces whether (and how) the collection exists in a space shared with the other home.
 */
data class CollectionSpec(
    val key: String,
    val location: RemoteLocation,
    val mapFields: Set<String> = emptySet(),
    val caregiverFilterField: String? = null,
    val parentsOnly: Boolean = false,
    val localOnly: Boolean = false,
    val spaces: SpaceSharing = SpaceSharing.None,
) {
    fun visibleTo(role: Role): Boolean = role == Role.Parent || !parentsOnly
}

object Collections {
    val Family = CollectionSpec("family", RemoteLocation.FamilyRoot, spaces = SpaceSharing.Separate)
    val Members = CollectionSpec("members", RemoteLocation.Flat("members"), spaces = SpaceSharing.Separate)
    val Children = CollectionSpec("children", RemoteLocation.Flat("children"), spaces = SpaceSharing.Merged)
    val Custody = CollectionSpec("custodySchedules", RemoteLocation.Flat("custodySchedules"), spaces = SpaceSharing.Merged)
    val Events = CollectionSpec(
        "events",
        RemoteLocation.Flat("events"),
        caregiverFilterField = "sharedWithCaregivers",
        spaces = SpaceSharing.Merged,
    )
    val PackingRules = CollectionSpec("packingRules", RemoteLocation.Flat("packingRules"), spaces = SpaceSharing.Merged)
    val PackingChecks = CollectionSpec(
        "packingChecks",
        RemoteLocation.Flat("packingChecks"),
        mapFields = setOf("checked"),
        spaces = SpaceSharing.Merged,
    )
    val MealPlans = CollectionSpec("mealPlans", RemoteLocation.Flat("mealPlans"), mapFields = setOf("entries"))
    val FavoriteWeeks = CollectionSpec("favoriteWeeks", RemoteLocation.Flat("favoriteWeeks"))
    val Recipes = CollectionSpec("recipes", RemoteLocation.Flat("recipes"))
    val ShoppingLists = CollectionSpec("shoppingLists", RemoteLocation.Flat("shoppingLists"))
    val ShoppingItems = CollectionSpec(
        "shoppingItems",
        RemoteLocation.Nested(
            parentCollection = "shoppingLists",
            parentRemotePath = "shoppingLists",
            parentField = "listId",
            subPath = "items",
        ),
    )
    val Stores = CollectionSpec("stores", RemoteLocation.Flat("stores"))
    val Pantry = CollectionSpec("pantry", RemoteLocation.Flat("pantry"))
    val Tasks =
        CollectionSpec(
            "tasks",
            RemoteLocation.Flat("tasks"),
            mapFields = setOf("completions"),
            caregiverFilterField = "sharedWithCaregivers",
            spaces = SpaceSharing.Merged,
        )
    val Subscriptions = CollectionSpec("icsSubscriptions", RemoteLocation.Flat("icsSubscriptions"), parentsOnly = true)
    val HandoffRequests = CollectionSpec("handoffRequests", RemoteLocation.Flat("handoffRequests"), spaces = SpaceSharing.Merged)
    val Presence = CollectionSpec("presence", RemoteLocation.Flat("presence"))
    val Settings = CollectionSpec("settings", RemoteLocation.Flat("settings"))
    val Diets = CollectionSpec("dietProfiles", RemoteLocation.Flat("dietProfiles"), spaces = SpaceSharing.Merged)
    val AisleOverrides = CollectionSpec("aisleOverrides", RemoteLocation.Flat("aisleOverrides"))
    val IngredientMappings = CollectionSpec("ingredientMappings", RemoteLocation.Flat("ingredientMappings"))
    val SchoolLunches = CollectionSpec(
        "schoolLunches",
        RemoteLocation.Flat("schoolLunches"),
        mapFields = setOf("days"),
        spaces = SpaceSharing.Merged,
    )
    val Wishes = CollectionSpec("wishes", RemoteLocation.Flat("wishes"), spaces = SpaceSharing.Merged)

    /** Veckopeng settings and paid weeks, one document per child; family money is for parents only. */
    val Allowances = CollectionSpec("allowances", RemoteLocation.Flat("allowances"), mapFields = setOf("paidWeeks"), parentsOnly = true)

    /** Pictures attached on this phone (ADR-025). The location is never used. */
    val Attachments = CollectionSpec("attachments", RemoteLocation.Flat("attachments"), localOnly = true)

    val all: List<CollectionSpec> = listOf(
        Family,
        Members,
        Children,
        Custody,
        Events,
        PackingRules,
        PackingChecks,
        MealPlans,
        FavoriteWeeks,
        Recipes,
        ShoppingLists,
        ShoppingItems,
        Stores,
        Pantry,
        Tasks,
        Subscriptions,
        HandoffRequests,
        Presence,
        Settings,
        Diets,
        AisleOverrides,
        IngredientMappings,
        SchoolLunches,
        Wishes,
        Allowances,
    )

    /** What a space shared with the other home holds (also enforced by the security rules). */
    val inSpaces: List<CollectionSpec> get() = all.filter { it.spaces != SpaceSharing.None }

    fun byKey(key: String): CollectionSpec? = all.firstOrNull { it.key == key }
}

/** Fields the sync layer adds to every Firestore document. Not part of domain JSON. */
object MetaFields {
    const val UPDATED_AT = "updatedAt"
    const val UPDATED_BY = "updatedBy"
    const val DELETED_AT = "deletedAt"
    val all = setOf(UPDATED_AT, UPDATED_BY, DELETED_AT)

    /** Placeholder value the remote store replaces with the server timestamp. */
    const val SERVER_TIMESTAMP = "__serverTimestamp__"
}

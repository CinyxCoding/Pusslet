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

/**
 * How one local collection syncs.
 * @param mapFields fields merged per key (per-key last-write-wins) instead of replaced as a whole.
 * @param caregiverFilterField caregivers only read documents where this boolean field is true.
 * @param parentsOnly caregivers do not read this collection at all.
 */
data class CollectionSpec(
    val key: String,
    val location: RemoteLocation,
    val mapFields: Set<String> = emptySet(),
    val caregiverFilterField: String? = null,
    val parentsOnly: Boolean = false,
) {
    fun visibleTo(role: Role): Boolean = role == Role.Parent || !parentsOnly
}

object Collections {
    val Family = CollectionSpec("family", RemoteLocation.FamilyRoot)
    val Members = CollectionSpec("members", RemoteLocation.Flat("members"))
    val Children = CollectionSpec("children", RemoteLocation.Flat("children"))
    val Custody = CollectionSpec("custodySchedules", RemoteLocation.Flat("custodySchedules"))
    val Events = CollectionSpec("events", RemoteLocation.Flat("events"), caregiverFilterField = "sharedWithCaregivers")
    val PackingRules = CollectionSpec("packingRules", RemoteLocation.Flat("packingRules"))
    val PackingChecks = CollectionSpec("packingChecks", RemoteLocation.Flat("packingChecks"), mapFields = setOf("checked"))
    val MealPlans = CollectionSpec("mealPlans", RemoteLocation.Flat("mealPlans"), mapFields = setOf("entries"))
    val FavoriteWeeks = CollectionSpec("favoriteWeeks", RemoteLocation.Flat("favoriteWeeks"))
    val Recipes = CollectionSpec("recipes", RemoteLocation.Flat("recipes"))

    /** "Kalas", "Matlådor": recipe ids merged per key, so two parents can fill the same collection at once. */
    val RecipeCollections = CollectionSpec("recipeCollections", RemoteLocation.Flat("recipeCollections"), mapFields = setOf("recipeIds"))
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
            mapFields = setOf("completions", "turnOverrides"),
            caregiverFilterField = "sharedWithCaregivers",
        )
    val Subscriptions = CollectionSpec("icsSubscriptions", RemoteLocation.Flat("icsSubscriptions"), parentsOnly = true)
    val HandoffRequests = CollectionSpec("handoffRequests", RemoteLocation.Flat("handoffRequests"), mapFields = setOf("declines"))
    val Presence = CollectionSpec("presence", RemoteLocation.Flat("presence"))
    val Settings = CollectionSpec("settings", RemoteLocation.Flat("settings"))

    /** Allergies and diets are sensitive: parents only (firestore.rules). */
    val Diets = CollectionSpec("dietProfiles", RemoteLocation.Flat("dietProfiles"), parentsOnly = true)
    val AisleOverrides = CollectionSpec("aisleOverrides", RemoteLocation.Flat("aisleOverrides"))
    val IngredientMappings = CollectionSpec("ingredientMappings", RemoteLocation.Flat("ingredientMappings"))
    val SchoolLunches = CollectionSpec("schoolLunches", RemoteLocation.Flat("schoolLunches"), mapFields = setOf("days"))

    /** A child home sick, per child and day (ADR-047); caregivers read it, so they know a pick-up is off. */
    val SickDays = CollectionSpec("sickDays", RemoteLocation.Flat("sickDays"))

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
        RecipeCollections,
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
        SickDays,
    )

    fun byKey(key: String): CollectionSpec? = all.firstOrNull { it.key == key }
}

/** Fields the sync layer adds to every Firestore document. Not part of domain JSON. */
object MetaFields {
    const val UPDATED_AT = "updatedAt"
    const val UPDATED_BY = "updatedBy"
    const val DELETED_AT = "deletedAt"

    /** Set by the nightly purge when a deleted document's content was removed (functions/src/purge.ts, ADR-046). */
    const val PURGED_AT = "purgedAt"
    val all = setOf(UPDATED_AT, UPDATED_BY, DELETED_AT, PURGED_AT)

    /** Placeholder value the remote store replaces with the server timestamp. */
    const val SERVER_TIMESTAMP = "__serverTimestamp__"
}

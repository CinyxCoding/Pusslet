package se.pusslet.data.usecase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import se.pusslet.core.AppConfig
import se.pusslet.core.model.Role
import se.pusslet.data.AppCoordinator
import se.pusslet.data.remote.AuthRepository
import se.pusslet.data.remote.CloudFunctions
import se.pusslet.data.repository.AttachmentFiles
import se.pusslet.data.session.SessionProvider
import se.pusslet.data.sync.SyncLocalStore
import kotlin.time.Clock

/**
 * GDPR helpers: export everything stored about the family on this device as JSON, and remove the family from the
 * device. Deleting the family/account on the server is done by Cloud Functions (deleteFamily / deleteAccount).
 */
class DataPrivacy(
    private val local: SyncLocalStore,
    private val sessions: SessionProvider,
    private val coordinator: AppCoordinator,
    private val functions: CloudFunctions,
    private val auth: AuthRepository,
    private val attachments: AttachmentFiles,
    private val clock: Clock = Clock.System,
) {
    /** Server deletion needs the backend and a signed-in account; without them everything is only on this phone. */
    val canDeleteOnServer: Boolean get() = functions.available && auth.currentUser.value != null

    val isParent: Boolean get() = sessions.session.value.role == Role.Parent
    private val json = Json { prettyPrint = true }

    suspend fun exportJson(): String {
        val familyId = sessions.session.value.familyId
        val docs = familyId?.let { local.listFamily(it) }.orEmpty()
        val byCollection = docs.groupBy { it.collection }
        val export = buildJsonObject {
            put("app", AppConfig.APP_NAME)
            put("exportedAt", clock.now().toString())
            put("format", 1)
            putJsonObject("collections") {
                byCollection.entries.sortedBy { it.key }.forEach { (collection, rows) ->
                    put(
                        collection,
                        JsonArray(
                            rows.sortedBy { it.docId }.map { row ->
                                val data = runCatching { json.parseToJsonElement(row.json).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                                JsonObject(
                                    data + mapOf(
                                        "_id" to JsonPrimitive(row.docId),
                                        "_deleted" to JsonPrimitive(row.deletedAt != null),
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), export)
    }

    fun exportFileName(): String = "${AppConfig.APP_NAME.lowercase()}-export-${clock.now().toString().take(10)}.json"

    /** Removes the family's data from this device only (the family continues for the others), pictures included. */
    suspend fun forgetOnDevice() {
        coordinator.forgetFamilyOnDevice(local)
        attachments.deleteAll()
    }

    /** "Radera familjen": gone for everyone on the server, then from this phone. */
    suspend fun deleteFamilyEverywhere() {
        val familyId = sessions.session.value.familyId ?: return
        functions.deleteFamily(familyId)
        auth.signOut()
        forgetOnDevice()
    }

    /** "Radera mitt konto": my account and membership are deleted; the family continues for the others. */
    suspend fun deleteAccount() {
        functions.deleteAccount()
        auth.signOut()
        forgetOnDevice()
    }
}

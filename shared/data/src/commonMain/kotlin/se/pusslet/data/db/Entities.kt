package se.pusslet.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every synced family document lives here as JSON (the domain model encoded with kotlinx.serialization).
 * Room is the single source of truth for the UI; the sync engine mirrors rows to Firestore.
 *
 * [collection] is the local collection key ("events", "shoppingItems" …), [docId] the document id.
 */
@Entity(
    tableName = "documents",
    primaryKeys = ["familyId", "collection", "docId"],
    indices = [Index("familyId", "collection", "deletedAt"), Index("pending")],
)
data class DocumentEntity(
    val familyId: String,
    val collection: String,
    val docId: String,
    val json: String,
    /** Server time (ms) of the last confirmed write, null until the first round-trip. */
    val updatedAt: Long? = null,
    val updatedBy: String? = null,
    val deletedAt: Long? = null,
    /** Local changes not yet acknowledged by the server. */
    val pending: Boolean = false,
    /** Device clock when this row was last written locally (ordering hint only). */
    val localUpdatedAt: Long = 0,
)

/** Queued outgoing changes: only the changed fields (dotted paths for per-key map merges), oldest first. */
@Entity(tableName = "outbox", indices = [Index("familyId", "collection", "docId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: String,
    val collection: String,
    val docId: String,
    /** JsonObject of fieldPath -> value; JSON null means "delete this field". */
    val fieldsJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** Incremental sync watermark per listened collection path. */
@Entity(tableName = "sync_state", primaryKeys = ["familyId", "path"])
data class SyncStateEntity(
    val familyId: String,
    val path: String,
    val lastServerTime: Long,
)

/** Generic HTTP/data cache for open data sources (forecasts, school units, lunch menus, ICS feeds, products). */
@Entity(tableName = "cache_entries")
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Last error message for this key (shown as "kunde inte uppdatera"), body stays the last good value. */
    val lastError: String? = null,
)

/** Livsmedelsverket's food list (~2 600 rows), searchable via [FoodFtsEntity]. */
@Entity(tableName = "foods")
data class FoodEntity(
    @PrimaryKey val number: Int,
    val name: String,
    /** "version" field from the API, used to refresh only what changed. */
    val version: String,
    val type: String? = null,
)

/**
 * unicode61, not the default "simple" tokenizer: "simple" only folds ASCII case, so "ägg" never found "Ägg …".
 * Diacritics are kept because å, ä and ö are letters of their own in Swedish ("ost" must not find "Östron").
 */
@Fts4(contentEntity = FoodEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=0"])
@Entity(tableName = "foods_fts")
data class FoodFtsEntity(
    val name: String,
)

/** Lazily fetched nutrition values per food (per 100 g), as JSON. */
@Entity(tableName = "food_nutrients")
data class FoodNutrientsEntity(
    @PrimaryKey val number: Int,
    val version: String,
    val json: String,
    val fetchedAt: Long,
)

/** Local purchase history used to suggest staples ("basvaror"). Never synced. */
@Entity(tableName = "purchase_log", indices = [Index("normalizedName")])
data class PurchaseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: String,
    val normalizedName: String,
    val displayName: String,
    val boughtAt: Long,
)

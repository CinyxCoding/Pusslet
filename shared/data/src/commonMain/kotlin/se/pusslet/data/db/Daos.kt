package se.pusslet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE familyId = :familyId AND collection = :collection AND deletedAt IS NULL")
    fun observeCollection(familyId: String, collection: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE familyId = :familyId AND collection = :collection AND docId = :docId")
    fun observe(familyId: String, collection: String, docId: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE familyId = :familyId AND collection = :collection AND docId = :docId")
    suspend fun get(familyId: String, collection: String, docId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE familyId = :familyId AND collection = :collection AND deletedAt IS NULL")
    suspend fun list(familyId: String, collection: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE familyId = :familyId")
    suspend fun listFamily(familyId: String): List<DocumentEntity>

    @Upsert
    suspend fun upsert(entity: DocumentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DocumentEntity>)

    @Query("DELETE FROM documents WHERE familyId = :familyId AND collection = :collection AND docId = :docId")
    suspend fun hardDelete(familyId: String, collection: String, docId: String)

    @Query("DELETE FROM documents WHERE familyId = :familyId")
    suspend fun deleteFamily(familyId: String)

    /** Deleted documents older than [before] with nothing waiting to be sent (ADR-046). Returns how many went. */
    @Query("DELETE FROM documents WHERE deletedAt IS NOT NULL AND deletedAt < :before AND pending = 0")
    suspend fun purgeDeleted(before: Long): Int

    @Query("UPDATE documents SET pending = 0 WHERE familyId = :familyId AND collection = :collection AND docId = :docId")
    suspend fun clearPending(familyId: String, collection: String, docId: String)

    @Query("SELECT COUNT(*) FROM documents WHERE pending = 1")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE familyId = :familyId ORDER BY id ASC LIMIT :limit")
    suspend fun next(familyId: String, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE familyId = :familyId AND collection = :collection AND docId = :docId ORDER BY id ASC")
    suspend fun forDoc(familyId: String, collection: String, docId: String): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE outbox SET fieldsJson = :fieldsJson WHERE id = :id")
    suspend fun replaceFields(id: Long, fieldsJson: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE familyId = :familyId")
    fun observeCount(familyId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE familyId = :familyId")
    suspend fun count(familyId: String): Int

    @Query("DELETE FROM outbox WHERE familyId = :familyId")
    suspend fun deleteFamily(familyId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT lastServerTime FROM sync_state WHERE familyId = :familyId AND path = :path")
    suspend fun watermark(familyId: String, path: String): Long?

    @Upsert
    suspend fun put(entity: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE familyId = :familyId")
    suspend fun deleteFamily(familyId: String)
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE `key` = :key")
    suspend fun get(key: String): CacheEntryEntity?

    @Query("SELECT * FROM cache_entries WHERE `key` = :key")
    fun observe(key: String): Flow<CacheEntryEntity?>

    @Upsert
    suspend fun put(entity: CacheEntryEntity)

    @Query("UPDATE cache_entries SET lastError = :error WHERE `key` = :key")
    suspend fun setError(key: String, error: String?)

    @Query("DELETE FROM cache_entries WHERE `key` LIKE :prefix || '%'")
    suspend fun deletePrefix(prefix: String)

    @Query("SELECT `key`, fetchedAt, lastError FROM cache_entries")
    suspend fun summaries(): List<CacheSummary>
}

data class CacheSummary(val key: String, val fetchedAt: Long, val lastError: String?)

@Dao
interface FoodDao {
    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int

    @Query("SELECT * FROM foods WHERE number = :number")
    suspend fun get(number: Int): FoodEntity?

    @Query("SELECT number, version FROM foods")
    suspend fun versions(): List<FoodVersion>

    @Upsert
    suspend fun upsertAll(foods: List<FoodEntity>)

    @Transaction
    @Query(
        """
        SELECT foods.* FROM foods JOIN foods_fts ON foods.rowid = foods_fts.rowid
        WHERE foods_fts MATCH :query LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food_nutrients WHERE number = :number")
    suspend fun nutrients(number: Int): FoodNutrientsEntity?

    @Upsert
    suspend fun putNutrients(entity: FoodNutrientsEntity)
}

data class FoodVersion(val number: Int, val version: String)

@Dao
interface PurchaseLogDao {
    @Insert
    suspend fun insert(entity: PurchaseLogEntity)

    @Query("SELECT * FROM purchase_log WHERE familyId = :familyId AND boughtAt >= :since ORDER BY boughtAt DESC")
    suspend fun since(familyId: String, since: Long): List<PurchaseLogEntity>

    @Query("DELETE FROM purchase_log WHERE familyId = :familyId")
    suspend fun deleteFamily(familyId: String)
}

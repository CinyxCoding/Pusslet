package se.pusslet.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * Removes cache entries by their exact key. [CacheDao.deletePrefix] uses LIKE, which also hits longer keys ("ics:a1" takes
 * "ics:a10" with it), ignores case and treats `_` as a wildcard. A separate DAO, so the hand-written [CacheDao] fakes in
 * the feature tests stay as they are.
 */
@Dao
interface CacheKeyDao {
    @Query("DELETE FROM cache_entries WHERE `key` IN (:keys)")
    suspend fun delete(keys: List<String>)
}

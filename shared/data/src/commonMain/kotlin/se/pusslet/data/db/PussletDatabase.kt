package se.pusslet.data.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

/** Small key/value table for device-local preferences (no DataStore dependency needed). */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface PreferenceDao {
    @Query("SELECT value FROM preferences WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entity: PreferenceEntity)

    @Query("DELETE FROM preferences WHERE `key` = :key")
    suspend fun remove(key: String)
}

@Database(
    entities = [PreferenceEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(PussletDatabaseConstructor::class)
abstract class PussletDatabase : RoomDatabase() {
    abstract fun preferenceDao(): PreferenceDao

    companion object {
        const val FILE_NAME = "pusslet.db"
    }
}

// The Room compiler generates the actual implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object PussletDatabaseConstructor : RoomDatabaseConstructor<PussletDatabase> {
    override fun initialize(): PussletDatabase
}

fun RoomDatabase.Builder<PussletDatabase>.configureCommon(): PussletDatabase = this
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

package se.pusslet.android

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import se.pusslet.data.db.FoodEntity
import se.pusslet.data.db.MIGRATION_1_2
import se.pusslet.data.db.PussletDatabase
import se.pusslet.data.sources.LivsmedelsverketFoodDatabase
import java.io.File
import kotlin.test.assertEquals

/** Livsmedelsverket's names start with a capital, so "ägg" must find "Ägg …" – also on a phone that had version 1. */
@RunWith(AndroidJUnit4::class)
class FoodSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val foods = listOf(
        FoodEntity(1, "Ägg hönsägg kokt", "1"),
        FoodEntity(2, "Äpple m. skal", "1"),
        FoodEntity(3, "Ål rökt", "1"),
        FoodEntity(4, "Ost hårdost fett 28%", "1"),
        FoodEntity(5, "Östron rå", "1"),
        FoodEntity(6, "Mjölk fett 3%", "1"),
    )

    private fun PussletDatabase.find(input: String) = runBlocking {
        foodDao().search(checkNotNull(LivsmedelsverketFoodDatabase.ftsQuery(input)), 10).map { it.number }
    }

    private fun assertFinds(db: PussletDatabase) {
        assertEquals(listOf(1), db.find("ägg"))
        assertEquals(listOf(2), db.find("ÄPPLE"))
        assertEquals(listOf(3), db.find("ål"))
        assertEquals(listOf(5), db.find("östron"))
        // å, ä and ö are letters of their own: "ost" is cheese, not oysters.
        assertEquals(listOf(4), db.find("ost"))
        assertEquals(listOf(6), db.find("mjölk"))
    }

    @Test
    fun newDatabaseFindsWordsStartingWithÅÄÖ() {
        val db = Room.inMemoryDatabaseBuilder<PussletDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        runBlocking { db.foodDao().upsertAll(foods) }
        assertFinds(db)
        db.close()
    }

    @Test
    fun versionOneDatabaseIsMigratedAndReindexed() {
        val file = File(context.cacheDir, "v1.db").apply { delete() }
        val schema = Json.parseToJsonElement(File("../shared/data/schemas/se.pusslet.data.db.PussletDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        AndroidSQLiteDriver().open(file.path).let { c ->
            schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { e ->
                val table = e.getValue("tableName").jsonPrimitive.content
                val statements =
                    listOf(e.getValue("createSql")) + (
                        e["indices"]?.jsonArray?.map {
                            it.jsonObject.getValue("createSql")
                        } ?: emptyList()
                    ) +
                        (e["contentSyncTriggers"]?.jsonArray ?: emptyList())
                statements.forEach { c.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
            foods.forEach { f -> c.execSQL("INSERT INTO foods(number, name, version) VALUES (${f.number}, '${f.name}', '1')") }
            c.execSQL("PRAGMA user_version = 1")
            c.close()
        }

        val db = Room.databaseBuilder<PussletDatabase>(
            context,
            file.path,
        ).setDriver(AndroidSQLiteDriver()).addMigrations(MIGRATION_1_2).build()
        assertFinds(db)
        // New rows still reach the index through the content-sync triggers.
        runBlocking { db.foodDao().upsertAll(listOf(FoodEntity(7, "Ärter gröna frysta", "1"))) }
        assertEquals(listOf(7), db.find("ärter"))
        db.close()
    }
}

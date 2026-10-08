package se.pusslet.data.db

import android.content.Context
import androidx.room.Room

fun createDatabase(context: Context): PussletDatabase {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(PussletDatabase.FILE_NAME)
    return Room.databaseBuilder<PussletDatabase>(context = appContext, name = dbFile.absolutePath).configureCommon()
}

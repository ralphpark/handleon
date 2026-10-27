package ai.handleon.car.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test fun v1ToV2PreservesEveryExistingTable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-v2-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, source TEXT NOT NULL, json TEXT NOT NULL, importKey TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_trips_importKey ON trips(importKey)")
            old.execSQL("CREATE TABLE labels (day TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL)")
            old.execSQL("CREATE TABLE resets (day TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(day,kind))")
            old.execSQL("CREATE TABLE places (cell INTEGER NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, PRIMARY KEY(cell,kind))")
            old.execSQL("CREATE TABLE reports (kind TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, updatedMs INTEGER NOT NULL)")
            old.execSQL("INSERT INTO trips VALUES(1,10,20,'CAR','original-trip','key')")
            old.execSQL("INSERT INTO labels VALUES('2026-10-01','SLEEP')")
            old.execSQL("INSERT INTO resets VALUES('2026-10-02','CAR_CHANGED')")
            old.execSQL("INSERT INTO places VALUES(1,'HOME',37.0,127.0)")
            old.execSQL("INSERT INTO reports VALUES('LONG','original-report',30)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, Db::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            assertEquals("original-trip", db.trips().all().single().json)
            assertEquals("key", db.trips().all().single().importKey)
            assertEquals("SLEEP", db.labels().all().single().kind)
            assertEquals("CAR_CHANGED", db.resets().all().single().kind)
            assertEquals(37.0, db.places().home()!!.lat)
            assertEquals("original-report", db.reports().get("LONG")!!.json)
            assertTrue(db.drivers().all().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

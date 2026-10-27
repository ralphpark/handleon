package ai.handleon.car.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, LabelEntity::class, ResetEntity::class, PlaceEntity::class, ReportEntity::class, TripDriverEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun drivers(): TripDriverDao
    abstract fun trips(): TripDao
    abstract fun labels(): LabelDao
    abstract fun resets(): ResetDao
    abstract fun places(): PlaceDao
    abstract fun reports(): ReportDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_drivers` (`startMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `observationJson` TEXT, `decidedMs` INTEGER, PRIMARY KEY(`startMs`))")
    }
}

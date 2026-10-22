package ai.handleon.car.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, LabelEntity::class, ResetEntity::class, PlaceEntity::class, ReportEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun labels(): LabelDao
    abstract fun resets(): ResetDao
    abstract fun places(): PlaceDao
    abstract fun reports(): ReportDao
}

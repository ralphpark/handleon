package ai.handleon.car.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY startMs, id")
    suspend fun all(): List<TripEntity>
}

@Dao
interface LabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(label: LabelEntity)

    @Query("DELETE FROM labels WHERE day = :day")
    suspend fun delete(day: String)

    @Query("SELECT * FROM labels ORDER BY day")
    suspend fun all(): List<LabelEntity>
}

@Dao
interface ResetDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reset: ResetEntity)

    @Query("SELECT * FROM resets ORDER BY day, kind")
    suspend fun all(): List<ResetEntity>
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE kind = 'HOME' LIMIT 1")
    suspend fun home(): PlaceEntity?

    @Query("SELECT cell FROM places WHERE kind = 'FAMILIAR'")
    suspend fun familiarCells(): List<Long>

    @Query("DELETE FROM places")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(places: List<PlaceEntity>)
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(report: ReportEntity)

    @Query("SELECT * FROM reports WHERE kind = :kind")
    suspend fun get(kind: String): ReportEntity?
}

@Dao
interface TripDriverDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(driver: TripDriverEntity)
    @Query("SELECT * FROM trip_drivers ORDER BY startMs")
    suspend fun all(): List<TripDriverEntity>
}

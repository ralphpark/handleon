package ai.handleon.car.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips", indices = [Index(value = ["importKey"], unique = true)])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val source: String,
    val json: String,
    val importKey: String?,
)

@Entity(tableName = "labels")
data class LabelEntity(@PrimaryKey val day: String, val kind: String)

@Entity(tableName = "resets", primaryKeys = ["day", "kind"])
data class ResetEntity(val day: String, val kind: String)

// 집과 익숙한 곳이 같은 셀에 있어도 서로 덮어쓰지 않는다.
@Entity(tableName = "places", primaryKeys = ["cell", "kind"])
data class PlaceEntity(val cell: Long, val kind: String, val lat: Double, val lon: Double)

@Entity(tableName = "reports")
data class ReportEntity(@PrimaryKey val kind: String, val json: String, val updatedMs: Long)

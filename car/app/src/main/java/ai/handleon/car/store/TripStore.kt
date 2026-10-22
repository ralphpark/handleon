package ai.handleon.car.store

import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.TripFeatures
import java.time.LocalDate

interface TripStore {
    suspend fun saveTrip(f: TripFeatures, importKey: String? = null): Boolean
    suspend fun trips(): List<TripFeatures>
    suspend fun labels(): Map<LocalDate, String>
    suspend fun setLabel(day: LocalDate, kind: String?)
    suspend fun resets(): List<Pair<LocalDate, String>>
    suspend fun addReset(day: LocalDate, kind: String)
    suspend fun home(): LatLon?
    suspend fun familiarCells(): Set<Long>
    suspend fun setPlaces(home: LatLon?, familiar: List<LatLon>)
    suspend fun saveReport(kind: String, json: String)
    suspend fun report(kind: String): String?
}

package ai.handleon.car.store

import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.TripFeatures
import java.time.LocalDate

interface TripStore {
    suspend fun saveTrip(f: TripFeatures, importKey: String? = null): Boolean
    suspend fun trips(): List<TripFeatures>
    suspend fun setDriver(startMs: Long, status: DriverStatus, observation: ai.handleon.driveprint.DriverObservation?, nowMs: Long)
    suspend fun pendingTrips(): List<TripFeatures>
    suspend fun driverStatuses(): Map<Long, DriverStatus>
    suspend fun answerDriver(startMs: Long, isOwner: Boolean, nowMs: Long)
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

fun ownerTrips(trips: List<TripFeatures>, statuses: Map<Long, DriverStatus>): List<TripFeatures> =
    trips.filter { statuses[it.startMs] == null || statuses[it.startMs] == DriverStatus.OWNER }

package ai.handleon.car.sync

import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.Features
import ai.handleon.driveprint.LatLon
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ImportFetcher(
    private val client: SupabaseClient,
    private val repo: TripStore,
    private val beforeSave: suspend () -> Unit = {},
) {
    suspend fun fetchAll(driverId: String): Int {
        var saved = 0
        for (path in client.listObjects("imports", "$driverId/")) {
            val content = client.download("imports", path) // A failed download must remain on the server.
            try {
                val trips = parseTrips(content)
                val home = repo.home()
                val familiar = repo.familiarCells()
                for (trip in trips) {
                    val features = Features.fromPastTrip(trip.startMs, trip.endMs, trip.start, trip.end,
                        trip.distance, home, familiar, ZoneId.of("Asia/Seoul"))
                    // Persist the dirty marker before inserting: a later failure/process stop must not lose it.
                    beforeSave()
                    if (repo.saveTrip(features, importKey = "TIMELINE:${trip.startMs}:${trip.endMs}")) saved++
                }
            } finally {
                // Delete even malformed/duplicate files, failed saves, and cancelled processing.
                withContext(NonCancellable) { client.delete("imports", path) }
            }
        }
        return saved
    }

    private data class PastTrip(val startMs: Long, val endMs: Long, val start: LatLon, val end: LatLon, val distance: Double)
    private fun parseTrips(content: String): List<PastTrip> {
        val file = try { Json.parseToJsonElement(content).jsonObject } catch (_: Exception) { return emptyList() }
        if ((file["version"] as? JsonPrimitive)?.intOrNull != 1) return emptyList()
        return (file["trips"] as? JsonArray).orEmpty().mapNotNull { item ->
            try {
                val row = item.jsonObject
                fun number(key: String) = row.getValue(key).jsonPrimitive.double
                val start = OffsetDateTime.parse(row.getValue("start").jsonPrimitive.content).toInstant().toEpochMilli()
                val end = OffsetDateTime.parse(row.getValue("end").jsonPrimitive.content).toInstant().toEpochMilli()
                val startPos = LatLon(number("startLat"), number("startLon"))
                val endPos = LatLon(number("endLat"), number("endLon"))
                val distance = number("distanceKm")
                if (end <= start || !valid(startPos) || !valid(endPos) || !distance.isFinite() || distance !in 0.0..1000.0) null
                else PastTrip(start, end, startPos, endPos, distance)
            } catch (_: Exception) { null }
        }
    }
    private fun valid(point: LatLon) = point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0
}

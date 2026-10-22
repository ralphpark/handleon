package ai.handleon.car.store

import androidx.room.withTransaction
import ai.handleon.driveprint.Geo
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.TripFeatures
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TripRepository(
    private val db: Db,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Asia/Seoul")) },
) : TripStore {
    override suspend fun saveTrip(f: TripFeatures, importKey: String?): Boolean =
        db.trips().insert(TripEntity(startMs = f.startMs, endMs = f.endMs,
            source = f.source.name, json = Json.encodeToString(f), importKey = importKey)) != -1L

    override suspend fun trips(): List<TripFeatures> =
        db.trips().all().map { Json.decodeFromString<TripFeatures>(it.json) }

    override suspend fun labels(): Map<LocalDate, String> =
        db.labels().all().associate { LocalDate.parse(it.day) to it.kind }

    override suspend fun setLabel(day: LocalDate, kind: String?) {
        db.withTransaction {
            if (kind == null) db.labels().delete(day.toString())
            else {
                db.labels().put(LabelEntity(day.toString(), kind))
                if (kind == "CAR_CHANGED") db.resets().insert(ResetEntity(day.toString(), kind))
            }
        }
    }

    override suspend fun resets(): List<Pair<LocalDate, String>> =
        db.resets().all().map { LocalDate.parse(it.day) to it.kind }

    override suspend fun addReset(day: LocalDate, kind: String) {
        db.resets().insert(ResetEntity(day.toString(), kind))
    }

    override suspend fun home(): LatLon? = db.places().home()?.let { LatLon(it.lat, it.lon) }

    override suspend fun familiarCells(): Set<Long> = db.places().familiarCells().toSet()

    override suspend fun setPlaces(home: LatLon?, familiar: List<LatLon>) {
        db.withTransaction {
            val previous = home()
            if (previous != null && home != null && Geo.haversineKm(previous, home) > 1.0) {
                val day = clock().withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate()
                db.resets().insert(ResetEntity(day.toString(), "HOME_CHANGED"))
            }
            val places = buildList {
                home?.let { add(PlaceEntity(Geo.cell(it), "HOME", it.lat, it.lon)) }
                familiar.distinctBy(Geo::cell).forEach {
                    add(PlaceEntity(Geo.cell(it), "FAMILIAR", it.lat, it.lon))
                }
            }
            db.places().clear()
            db.places().insert(places)
        }
    }

    override suspend fun saveReport(kind: String, json: String) {
        db.reports().put(ReportEntity(kind, json, clock().toInstant().toEpochMilli()))
    }

    override suspend fun report(kind: String): String? = db.reports().get(kind)?.json
}

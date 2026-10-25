package ai.handleon.car.sync

import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.Patterns
import ai.handleon.driveprint.TripFeatures
import ai.handleon.car.ui.SharingUi
import ai.handleon.car.ui.CarNote
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class SyncResult(val synced: Boolean = false, val imported: Int = 0)

class CarSync(
    private val client: SupabaseClient,
    private val state: SyncState,
    private val repo: TripStore,
    private val engineRunner: EngineRunner,
    private val mode: () -> Mode,
    private val presentation: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val pairing = Pairing(client)
    private val importer = ImportFetcher(client, repo) { withContext(Dispatchers.IO) { state.setImportsPending(true) } }
    private val uploader = Uploader(client)
    private val mutex = Mutex()
    private val zone = ZoneId.of("Asia/Seoul")

    // RPCs resolve the owner from the car's anonymous account; never send a driver identifier.
    private fun linked() = client.configured && state.driverId != null
    suspend fun sharingState(): SharingUi? =
        if (linked()) SharingUi.fromJson(client.rpc("get_sharing_state")) else null

    suspend fun setSharingPaused(paused: Boolean) {
        if (linked()) client.rpc("set_sharing_paused", buildJsonObject { put("p_paused", paused) })
    }

    suspend fun revokeAllShares(): Int? =
        if (linked()) client.rpc("revoke_all_shares").jsonPrimitive.int else null

    suspend fun notes(): List<CarNote>? =
        if (linked()) client.rpc("get_car_notes").jsonArray.map(CarNote::fromJson) else null

    suspend fun markNoteSeen(id: Long) {
        if (linked()) client.rpc("mark_note_seen", buildJsonObject { put("p_id", id) })
    }

    suspend fun savedLetter(): String? = mutex.withLock {
        val driver = state.driverId ?: return@withLock null
        if (state.state.value.letterDriverId != driver) return@withLock null
        repo.report("LETTER").takeIf { state.driverId == driver && state.state.value.letterDriverId == driver }
    }

    suspend fun syncOnce(): SyncResult = mutex.withLock {
        val driver = connectedDriver() ?: return@withLock SyncResult()
        val currentMode = mode()
        var imported = 0
        try {
            imported = importer.fetchAll(driver)
        } finally {
            // Also recover partial imports, failed deletion, and a previous failed engine run.
            // A duplicate file can conservatively trigger one rebuild; unique trip keys still deduplicate data.
            if (imported > 0 || state.importsPending) {
                engineRunner.run(currentMode)
                withContext(Dispatchers.IO) { state.setImportsPending(false) }
            }
        }
        // 발표 모드에서는 서버에 재생 결과만 둔다(uploadReplay). 가져오기는 원본 위치 파일을 지우려고 위에서 계속 받는다.
        if (presentation()) return@withLock SyncResult(imported = imported)
        when (currentMode) {
            Mode.MAEUM -> repo.report("LONG")?.let {
                uploadMonth(driver, Json.decodeFromString<LongReport>(it), "car")
            }
            Mode.CONDITION -> repo.report("CONDITION")?.let {
                uploader.upload(driver, Json.decodeFromString<ConditionReport>(it))
            }
        }
        val longReport = repo.report("LONG")?.let { Json.decodeFromString<LongReport>(it) }
        uploader.uploadPatterns(driver, Patterns.monthly(repo.trips(), longReport, zone,
            Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()), "car")
        state.markSynced(driver, nowMs())
        SyncResult(synced = true, imported = imported)
    }

    /** Called only after playback has emitted its final frame; presentation is checked here too. */
    suspend fun uploadReplay(report: LongReport, trips: List<TripFeatures>) = mutex.withLock {
        if (!presentation()) return@withLock
        val driver = connectedDriver() ?: return@withLock
        uploadMonth(driver, report, "replay")
        trips.maxOfOrNull { it.startMs }?.let { last ->
            val today = YearMonth.from(Instant.ofEpochMilli(last).atZone(zone)).plusMonths(1).atDay(1)
            // Include six completed replay months, not a fabricated empty current month.
            val months = Patterns.monthly(trips, report, zone, today, months = 7).filterNot { it.partial }
            uploader.uploadPatterns(driver, months, "replay")
        }
        state.markSynced(driver, nowMs())
    }

    private suspend fun connectedDriver(): String? {
        if (!client.configured || state.driverId == null) return null
        client.ensureSession()
        val driver = pairing.linkedDriver()
        if (driver != state.driverId) {
            state.setDriver(driver)
            repo.saveReport("LETTER", "{}")
        }
        return driver
    }

    private suspend fun uploadMonth(driver: String, report: LongReport, source: String) {
        val row = uploader.upload(driver, report, Mode.MAEUM, source) ?: return
        // Do not leave an old month's/old summary's letter visible after a failed new generation.
        state.setLetterDriver(null)
        repo.saveReport("LETTER", "{}")
        val letter = uploader.letterFor(driver, row, report) ?: return
        if (state.driverId != driver) return
        repo.saveReport("LETTER", buildJsonObject {
            put("month", row.getValue("month")); put("text", letter)
        }.toString())
        state.setLetterDriver(driver)
    }
}

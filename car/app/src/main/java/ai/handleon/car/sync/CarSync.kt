package ai.handleon.car.sync

import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.LongReport
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
        when (currentMode) {
            Mode.MAEUM -> repo.report("LONG")?.let {
                uploadMonth(driver, Json.decodeFromString<LongReport>(it), "car")
            }
            Mode.CONDITION -> repo.report("CONDITION")?.let {
                uploader.upload(driver, Json.decodeFromString<ConditionReport>(it))
            }
        }
        state.markSynced(driver, nowMs())
        SyncResult(synced = true, imported = imported)
    }

    /** Called only after playback has emitted its final frame; presentation is checked here too. */
    suspend fun uploadReplay(report: LongReport) = mutex.withLock {
        if (!presentation()) return@withLock
        val driver = connectedDriver() ?: return@withLock
        uploadMonth(driver, report, "replay")
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

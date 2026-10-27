package ai.handleon.car.sync

import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.Habits
import ai.handleon.driveprint.Promises
import ai.handleon.driveprint.Promise
import ai.handleon.driveprint.WeeklyReport
import java.time.LocalDate
import ai.handleon.driveprint.Outings
import ai.handleon.driveprint.Source
import kotlinx.serialization.encodeToString
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
    private val selectedPromises: () -> Set<Promise> = { emptySet() },
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

    suspend fun stopDriveNotices() {
        if (linked()) client.rpc("stop_drive_notices").jsonPrimitive.int
    }

    suspend fun reportOutings() = mutex.withLock { reportOutingsLocked() }

    private suspend fun reportOutingsLocked() {
        val driver = state.driverId ?: return
        if (!linked() || presentation()) return
        val key = "DRIVE_NOTICES:$driver"
        var ledger = repo.report(key)?.let { Json.decodeFromString<DriveNoticeLedger>(it) } ?: DriveNoticeLedger()
        val trips = repo.trips().filter { it.source == Source.CAR }
        val ownerStarts = trips.map { it.startMs }.toSet()
        val outings = Outings.group(trips, nowMs())
        for ((outingKey, outing) in ledger.attempted) {
            val corrected = outing.tripStarts.any { it !in ownerStarts }
            val replaced = !corrected && outings.any { current ->
                current.key != outingKey && current.tripStarts.any { it in outing.tripStarts }
            }
            if (outingKey !in ledger.retracted && (corrected || replaced)) {
                client.rpc("retract_drive", buildJsonObject { put("p_outing_key", outingKey) })
                ledger = ledger.copy(retracted = ledger.retracted + outingKey,
                    replaced = if (replaced) ledger.replaced + outingKey else ledger.replaced)
                repo.saveReport(key, Json.encodeToString(ledger))
            }
        }
        // OTHER corrections suppress the whole old outing; a replaced key may join its new owner group.
        val retractedStarts = (ledger.retracted - ledger.replaced).flatMap { ledger.attempted[it]?.tripStarts.orEmpty() }.toSet()
        for (outing in DriveNotices.toReport(outings, nowMs())) {
            if (outing.key in ledger.retracted || outing.tripStarts.any { it in retractedStarts }) continue
            ledger = ledger.copy(attempted = ledger.attempted + (outing.key to outing))
            repo.saveReport(key, Json.encodeToString(ledger))
            uploader.reportOuting(outing)
        }
    }

    suspend fun retractOuting(outingKey: String) = mutex.withLock {
        if (!linked() || presentation()) return@withLock
        require(outingKey.matches(Regex("[0-9]{10,16}")))
        client.rpc("retract_drive", buildJsonObject { put("p_outing_key", outingKey) })
        val key = "DRIVE_NOTICES:${state.driverId}"
        val ledger = repo.report(key)?.let { Json.decodeFromString<DriveNoticeLedger>(it) } ?: DriveNoticeLedger()
        repo.saveReport(key, Json.encodeToString(ledger.copy(retracted = ledger.retracted + outingKey)))
    }

    /** Serialize answers with uploads, so a sync cannot report an owner snapshot after a correction. */
    suspend fun answerDriver(startMs: Long, isOwner: Boolean) = mutex.withLock {
        repo.answerDriver(startMs, isOwner, nowMs())
        engineRunner.run(mode())
        reportOutingsLocked()
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
        uploadWeekly(driver, currentMode)
        reportOutingsLocked()
        state.markSynced(driver, nowMs())
        SyncResult(synced = true, imported = imported)
    }

    private suspend fun uploadWeekly(driver: String, currentMode: Mode) {
        val now = nowMs()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val weeklyKey = "WEEKLY_UPLOADED:$driver"
        val published = repo.report(weeklyKey)?.let { Json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
        if (currentMode == Mode.MAEUM || published.isNotEmpty()) {
            // Changing the screen mode must not strand already shared facts after an owner correction.
            val report = if (currentMode == Mode.CONDITION) engineRunner.refreshWeekly(today)
                else repo.report("WEEKLY")?.let { Json.decodeFromString<WeeklyReport>(it) }
            if (report != null) {
                val key = weeklyKey
                val previous = published
                val publishedDates = published.map { it.jsonObject.getValue("week_start").jsonPrimitive.content }.toSet()
                val rows = weeklyChangeRows(driver, report, "car").filter {
                    currentMode == Mode.MAEUM || it.jsonObject.getValue("week_start").jsonPrimitive.content in publishedDates
                }
                val dates = rows.map { it.jsonObject.getValue("week_start").jsonPrimitive.content }.toSet()
                // A corrected/now-excluded week must not leave a previously shared flag behind.
                val cleared = previous.filter {
                    val date = it.jsonObject.getValue("week_start").jsonPrimitive.content
                    date !in dates && LocalDate.parse(date) >= monday.minusWeeks(12)
                }.map { buildJsonObject {
                    put("driver_id", driver); put("week_start", it.jsonObject.getValue("week_start")); put("flagged", false)
                    put("domains", JsonArray(emptyList())); put("top", JsonArray(emptyList())); put("source", "car")
                } }
                val outgoing = JsonArray((rows + cleared).sortedBy { it.jsonObject.getValue("week_start").jsonPrimitive.content })
                repo.saveReport(key, outgoing.toString())
                uploader.uploadWeeklyChanges(outgoing)
            }
        }
        val key = "HABITS_UPLOADED:$driver"
        val previous = repo.report(key)?.let { Json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
        val trips = repo.trips().filter { it.source == Source.CAR && it.startMs <= now && it.endMs <= now }
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun week(day: LocalDate) = day.minusDays(day.dayOfWeek.value - 1L)
        val first = (trips.map { week(day(it.startMs)) } + previous.map { LocalDate.parse(it.jsonObject.getValue("week_start").jsonPrimitive.content) })
            .minOrNull()?.coerceAtLeast(monday.minusWeeks(7)) ?: return
        val outings = Outings.group(trips, now)
        val selected = selectedPromises()
        val summaries = generateSequence(first) { it.plusWeeks(1) }.takeWhile { it <= monday }.map { from ->
            val to = from.plusWeeks(1)
            val inWeek: (Long) -> Boolean = { day(it) >= from && day(it) < to }
            Habits.summary(trips, outings, from, to, zone) to Promises.evaluate(selected,
                trips.filter { inWeek(it.startMs) }, outings.filter { inWeek(it.startMs) })
        }.toList()
        val rows = weeklyHabitRows(driver, summaries, "car")
        repo.saveReport(key, rows.toString())
        uploader.uploadWeeklyHabits(rows)
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

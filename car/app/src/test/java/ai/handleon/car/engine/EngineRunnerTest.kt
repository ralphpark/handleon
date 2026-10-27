package ai.handleon.car.engine

import ai.handleon.car.replay.ReplayParser
import ai.handleon.car.store.*
import ai.handleon.driveprint.FeatureId
import ai.handleon.driveprint.Domain
import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.ConditionStatus
import ai.handleon.driveprint.Geo
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.Stage
import ai.handleon.driveprint.TripFeatures
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EngineRunnerTest {
    @Test fun fatherDataGivesTalkInMaeumMode() = runTest {
        val store = MemoryStore(load("father"))
        val runner = runner(store)
        val state = runner.run(Mode.MAEUM)
        assertEquals(Stage.TALK, state.long!!.stage)
        assertNull(state.condition)
        assertEquals(state, runner.state.value)
        assertEquals(NOW.toInstant().toEpochMilli(), state.updatedMs)
        assertEquals(setOf("LONG", "WEEKLY"), store.reports.keys)
        assertEquals(state.long, Json.decodeFromString<LongReport>(store.reports.getValue("LONG")))
    }

    @Test fun labeledDayIsPassedToEngine() = runTest {
        // 매달 1일/2일 20회씩 운전하되 마지막 달의 2일은 1회. 2일을 모두 제외하면 평소와 같다.
        // 위치가 없는 기록으로 구성해 이동 횟수 변화가 다른 위치 지표에 희석되지 않게 한다.
        val template = load("father").first().copy(distanceKm = 10.0, night = false, rain = false,
            endCell = null, maxFromHomeKm = null)
        val months = (1..8).map { YearMonth.of(2026, it) }
        val trips = months.flatMap { month ->
            (1..2).flatMap { day ->
                List(if (month.monthValue == 8 && day == 2) 1 else 20) { index ->
                    val start = month.atDay(day).atTime(9, 0).atZone(KST).toInstant().toEpochMilli() + index * 60_000L
                    template.copy(startMs = start, endMs = start + 30_000)
                }
            }
        }
        val store = MemoryStore(trips)
        val runner = runner(store, "2026-09-22T09:00:00+09:00")
        assertEquals(Stage.WATCH, runner.run(Mode.MAEUM).long!!.stage)
        months.forEach { store.setLabel(it.atDay(2), "SLEEP") }
        assertEquals(Stage.STABLE, runner.run(Mode.MAEUM).long!!.stage)
    }

    @Test fun conditionModeSkipsLongTrack() = runTest {
        val store = MemoryStore(load("child"))
        val state = runner(store).run(Mode.CONDITION)
        assertNull(state.long)
        assertEquals(ConditionStatus.CAUTION, state.condition!!.status)
        assertEquals(setOf("CONDITION"), store.reports.keys)
        assertEquals(state.condition, Json.decodeFromString<ConditionReport>(store.reports.getValue("CONDITION")))
    }

    @Test fun labeledDayIsAlsoPassedToConditionTrack() = runTest {
        val store = MemoryStore(load("child"))
        store.setLabel(LocalDate.of(2026, 10, 22), "SLEEP")
        val result = runner(store).run(Mode.CONDITION).condition!!
        assertEquals(ConditionStatus.OK, result.status)
        assertEquals(false, result.days.last().alert)
    }

    @Test fun homeAndCarChangesKeepOtherLongTrackBaselines() = runTest {
        val baseline = runner(MemoryStore(load("father"))).run(Mode.MAEUM).long!!
        for (kind in listOf("HOME_CHANGED", "CAR_CHANGED")) {
            val store = MemoryStore(load("father"))
            store.addReset(LocalDate.of(2026, 9, 15), kind)
            val report = runner(store).run(Mode.MAEUM).long!!
            assertEquals(baseline.baselines[FeatureId.M1], report.baselines[FeatureId.M1])
            assertEquals(baseline.baselines[FeatureId.W3], report.baselines[FeatureId.W3])
            if (kind == "CAR_CHANGED") assertNull(report.baselines[FeatureId.O7])
            else assertEquals(baseline.baselines[FeatureId.O7], report.baselines[FeatureId.O7])
        }
        val all = MemoryStore(load("father"))
        all.addReset(LocalDate.of(2026, 9, 15), "ALL")
        assertEquals(Stage.BUILDING, runner(all).run(Mode.MAEUM).long!!.stage)
    }

    @Test fun onlyCarAndAllResetConditionOnTheDay() = runTest {
        val baseline = runner(MemoryStore(load("child"))).run(Mode.CONDITION).condition!!
        for (kind in listOf("HOME_CHANGED", "CAR_CHANGED", "ALL")) {
            val store = MemoryStore(load("child"))
            store.addReset(LocalDate.of(2026, 10, 22), kind)
            val report = runner(store).run(Mode.CONDITION).condition!!
            if (kind == "HOME_CHANGED") assertEquals(baseline, report)
            else assertEquals(ConditionStatus.BUILDING, report.status, kind)
        }
    }

    @Test fun clockIsConvertedToSeoulBeforeChoosingMonthAndDay() = runTest {
        val monthly = runner(MemoryStore(load("father")), "2026-10-31T15:30:00Z").run(Mode.MAEUM)
        assertEquals(YearMonth.of(2026, 10), monthly.long!!.months.last().month)
        assertEquals(YearMonth.of(2026, 11), monthly.long!!.inProgress!!.month)
        val daily = runner(MemoryStore(load("child")), "2026-10-21T15:30:00Z").run(Mode.CONDITION)
        assertEquals(LocalDate.of(2026, 10, 22), daily.condition!!.days.last().day)
    }

    @Test fun switchingModesClearsTheOtherReportFromState() = runTest {
        val runner = runner(MemoryStore(load("father")))
        assertNotNull(runner.run(Mode.MAEUM).long)
        assertNull(runner.run(Mode.CONDITION).long)
        assertNotNull(runner.state.value.condition)
        assertNull(runner.run(Mode.MAEUM).condition)
    }

    @Test fun failedSaveDoesNotPublishUnpersistedState() = runTest {
        val store = MemoryStore(load("child"))
        val runner = runner(store)
        val previous = runner.run(Mode.CONDITION)
        store.failSave = true
        assertFailsWith<IOException> { runner.run(Mode.MAEUM) }
        assertEquals(previous, runner.state.value)
    }

    @Test fun `weekly report and readiness use the same labels and resets`() = runTest {
        val trips = load("father")
        val store = MemoryStore(trips)
        store.setLabel(LocalDate.of(2026, 10, 1), "SLEEP")
        store.addReset(LocalDate.of(2026, 10, 1), "ALL")
        val state = runner(store).run(Mode.MAEUM)
        val engine = ai.handleon.driveprint.Driveprint()
        val labels = store.labels().keys
        val resets = setOf(ai.handleon.driveprint.Reset(LocalDate.of(2026, 10, 1), ai.handleon.driveprint.ResetKind.ALL))
        val weekly = engine.weeklyReport(trips, labels, resets, NOW.toLocalDate())
        assertEquals(weekly, state.weekly)
        assertEquals(weekly, Json.decodeFromString<ai.handleon.driveprint.WeeklyReport>(store.reports.getValue("WEEKLY")))
        assertEquals(engine.readiness(trips, weekly, NOW.toLocalDate(), labels, resets), state.readiness)
        assertNull(runner(store).run(Mode.CONDITION).weekly)
    }

    private fun load(persona: String) = ReplayParser.parse(File("src/main/assets/replay/$persona.json").readText()).trips
    private fun runner(store: TripStore, now: String = NOW.toString()) =
        EngineRunner(store, clock = { ZonedDateTime.parse(now) })

    private companion object {
        val KST = ZoneId.of("Asia/Seoul")
        val NOW = ZonedDateTime.parse("2026-10-22T09:00:00+09:00")
    }
}

private class MemoryStore(trips: List<TripFeatures>) : TripStore {
    private val storedTrips = trips.toMutableList()
    private val keys = mutableSetOf<String>()
    private val storedLabels = mutableMapOf<LocalDate, String>()
    private val storedResets = mutableSetOf<Pair<LocalDate, String>>()
    private var storedHome: LatLon? = null
    private var familiar = emptySet<Long>()
    val reports = mutableMapOf<String, String>()
    var failSave = false

    override suspend fun saveTrip(f: TripFeatures, importKey: String?): Boolean {
        if (importKey != null && !keys.add(importKey)) return false
        storedTrips.add(f)
        return true
    }
    val drivers = mutableMapOf<Long, DriverStatus>()
    override suspend fun trips() = ownerTrips(storedTrips.toList(), drivers)
    override suspend fun setDriver(startMs: Long, status: DriverStatus, observation: ai.handleon.driveprint.DriverObservation?, nowMs: Long) { drivers[startMs] = status }
    override suspend fun pendingTrips() = storedTrips.filter { drivers[it.startMs] == DriverStatus.PENDING }
    override suspend fun driverStatuses() = drivers.toMap()
    override suspend fun answerDriver(startMs: Long, isOwner: Boolean, nowMs: Long) { drivers[startMs] = if (isOwner) DriverStatus.OWNER else DriverStatus.OTHER }
    override suspend fun labels() = storedLabels.toMap()
    override suspend fun setLabel(day: LocalDate, kind: String?) {
        if (kind == null) storedLabels.remove(day) else storedLabels[day] = kind
        if (kind == "CAR_CHANGED") addReset(day, kind)
    }
    override suspend fun resets() = storedResets.toList()
    override suspend fun addReset(day: LocalDate, kind: String) { storedResets.add(day to kind) }
    override suspend fun home() = storedHome
    override suspend fun familiarCells() = familiar
    override suspend fun setPlaces(home: LatLon?, familiar: List<LatLon>) {
        storedHome = home
        this.familiar = familiar.map(Geo::cell).toSet()
    }
    override suspend fun saveReport(kind: String, json: String) {
        if (failSave) throw IOException("시험용 저장 실패")
        reports[kind] = json
    }
    override suspend fun report(kind: String) = reports[kind]
}

package ai.handleon.car.sync

import ai.handleon.car.engine.*
import ai.handleon.car.store.DriverStatus
import ai.handleon.driveprint.*
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Test
import kotlin.test.*

class WeeklySyncTest {
    @Test fun `condition mode rebuilds previously published weekly facts after a driver correction`() = runTest {
        val server = MockWebServer()
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests.add(path to request.body.readUtf8())
                return MockResponse().setBody(when (path) {
                    "/rest/v1/car_links" -> """[{"driver_id":"driver"}]"""
                    "/storage/v1/object/list/imports" -> "[]"
                    else -> "null"
                })
            }
        }
        try {
            val now = Instant.parse("2026-10-27T09:00:00Z").toEpochMilli()
            val repo = MemoryTrips()
            val t = patternTrip().copy(startMs = now - 7 * 86400000L, endMs = now - 7 * 86400000L + 60000)
            repo.saved += t
            repo.saveReport("WEEKLY", Json.encodeToString(WeeklyReport(WeeklyStatus.FLAGGED,
                listOf(WeekResult(LocalDate.of(2026, 10, 19), setOf(Domain.MOBILITY), true, true)), null, 0, emptyList())))
            val state = SyncState(MemoryPreferences()).also { it.setDriver("driver") }
            var mode = Mode.MAEUM
            val runner = EngineRunner(repo)
            fun sync() = CarSync(server.client(), state, repo, runner, { mode }, { false }, nowMs = { now })
            sync().syncOnce()
            mode = Mode.CONDITION
            sync().answerDriver(t.startMs, false)
            requests.clear()
            sync().syncOnce()
            val rows = requests.filter { it.first == "/rest/v1/weekly_changes" }
            assertEquals(1, rows.size, "The old shared week must be corrected in CONDITION too")
            val change = Json.parseToJsonElement(rows.single().second).jsonArray.single().jsonObject
            assertFalse(change["flagged"]!!.jsonPrimitive.boolean)
            assertTrue(change["domains"]!!.jsonArray.isEmpty())
            assertNull(runner.state.value.weekly, "The CONDITION screen must stay in its selected mode")
        } finally { server.shutdown() }
    }
    @Test fun `sync uploads weekly tables after patterns and clears corrected owner facts`() = runTest {
        val server = MockWebServer()
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests.add(path to request.body.readUtf8())
                return MockResponse().setBody(when (path) {
                    "/rest/v1/car_links" -> """[{"driver_id":"driver"}]"""
                    "/storage/v1/object/list/imports" -> "[]"
                    else -> "null"
                })
            }
        }
        try {
            val now = Instant.parse("2026-10-27T09:00:00Z").toEpochMilli()
            val repo = MemoryTrips()
            val t = patternTrip().copy(startMs = now - 7 * 86400000L, endMs = now - 7 * 86400000L + 60000)
            repo.saved += t
            val r = WeeklyReport(WeeklyStatus.FLAGGED, listOf(WeekResult(LocalDate.of(2026, 10, 19), setOf(Domain.MOBILITY), true, true)), null, 0, emptyList())
            repo.saveReport("WEEKLY", Json.encodeToString(r))
            val state = SyncState(MemoryPreferences()).also { it.setDriver("driver") }
            fun sync() = CarSync(server.client(), state, repo, EngineRunner(repo), { Mode.MAEUM }, { false },
                selectedPromises = { setOf(Promise.NO_NIGHT) }, nowMs = { now })
            sync().syncOnce()
            val paths = requests.map { it.first }
            assertTrue(paths.indexOf("/rest/v1/monthly_patterns") < paths.indexOf("/rest/v1/weekly_changes"))
            assertTrue(paths.indexOf("/rest/v1/weekly_changes") < paths.indexOf("/rest/v1/weekly_habits"))
            repo.setDriver(t.startMs, DriverStatus.OTHER, null, now)
            repo.saveReport("WEEKLY", Json.encodeToString(r.copy(status = WeeklyStatus.BUILDING, weeks = emptyList())))
            requests.clear()
            sync().syncOnce()
            val change = Json.parseToJsonElement(requests.single { it.first == "/rest/v1/weekly_changes" }.second).jsonArray.single().jsonObject
            assertFalse(change["flagged"]!!.jsonPrimitive.boolean)
            val habits = Json.parseToJsonElement(requests.single { it.first == "/rest/v1/weekly_habits" }.second).jsonArray
            assertTrue(habits.all { it.jsonObject["trips"]!!.jsonPrimitive.int == 0 })
        } finally { server.shutdown() }
    }
}

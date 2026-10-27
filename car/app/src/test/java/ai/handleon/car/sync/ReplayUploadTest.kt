package ai.handleon.car.sync

import ai.handleon.car.engine.*
import ai.handleon.car.replay.ReplayParser
import ai.handleon.driveprint.*
import java.io.File
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayUploadTest {
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val state = SyncState(MemoryPreferences()).apply { setDriver("driver") }
    private val presentation = AtomicBoolean(true)
    private val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var hook: (RecordedRequest) -> Unit = {}
    private var response: (RecordedRequest) -> MockResponse? = { null }
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 6, 22)
    private fun trips() = ReplayParser.parse(File("src/main/assets/replay_sudden.json").readText()).trips
        .filter { it.endMs < today.atStartOfDay(zone).toInstant().toEpochMilli() }
    private fun report() = Driveprint().longReport(trips(), emptySet(), emptySet(), today)
    private fun sync(tokens: TokenStore = MemoryTokens(session())): CarSync {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request; hook(request)
                response(request)?.let { return it }
                return MockResponse().setBody(when {
                    request.requestUrl!!.encodedPath == "/auth/v1/token" -> auth()
                    request.requestUrl!!.encodedPath == "/rest/v1/car_links" -> """[{"driver_id":"driver"}]"""
                    request.method == "GET" -> "[]"
                    request.requestUrl!!.encodedPath == "/functions/v1/monthly-letter" -> """{"letter":"재생 편지"}"""
                    else -> "null"
                })
            }
        }
        return CarSync(server.client(tokens), state, repo, EngineRunner(repo), { Mode.MAEUM }, presentation::get,
            selectedPromises = { error("Real promises must not enter replay") })
    }
    @After fun close() = server.shutdown()

    @Test fun `replay uploads both weekly tables with the exact C4 contract and no real records`() = runTest {
        repo.saved += List(200) { patternTrip() }
        repo.reports["WEEKLY"] = "real-weekly"; repo.reports["LONG"] = "real-long"
        val before = repo.saved.toList()
        assertTrue(sync().uploadReplay(report(), trips(), today))
        val changes = requests.single { it.requestUrl!!.encodedPath == "/rest/v1/weekly_changes" }
        val habits = requests.single { it.requestUrl!!.encodedPath == "/rest/v1/weekly_habits" }
        assertEquals("driver_id,week_start", changes.requestUrl!!.queryParameter("on_conflict"))
        assertEquals("driver_id,week_start", habits.requestUrl!!.queryParameter("on_conflict"))
        val rows = Json.parseToJsonElement(changes.body.readUtf8()).jsonArray
        assertEquals(weeklyChangeRows("driver", Driveprint().weeklyReport(trips(), emptySet(), emptySet(), today), "replay"), rows)
        assertTrue(rows.any { it.jsonObject.getValue("flagged").jsonPrimitive.boolean })
        rows.forEach { value ->
            val row = value.jsonObject
            assertEquals(setOf("driver_id", "week_start", "flagged", "domains", "top", "source"), row.keys)
            assertEquals("replay", row.getValue("source").jsonPrimitive.content)
            assertTrue(row.getValue("top").jsonArray.size <= 3)
            row.getValue("top").jsonArray.forEach { assertEquals(setOf("feature", "direction"), it.jsonObject.keys) }
            if (row.getValue("flagged").jsonPrimitive.boolean) assertTrue(row.getValue("domains").jsonArray.isNotEmpty())
        }
        val habitRows = Json.parseToJsonElement(habits.body.readUtf8()).jsonArray
        assertEquals(8, habitRows.size)
        habitRows.forEach { value ->
            val row = value.jsonObject
            assertEquals(setOf("driver_id", "week_start", "trips", "minutes", "signal_kept", "signal_total", "hard_brakes",
                "hard_accels", "calm_trips", "long_stretches", "night_trips", "promises_kept", "promises_total", "story", "source"), row.keys)
            assertEquals("replay", row.getValue("source").jsonPrimitive.content)
            val from = LocalDate.parse(row.getValue("week_start").jsonPrimitive.content)
            assertEquals(DayOfWeek.MONDAY, from.dayOfWeek)
            val count = trips().count { t -> Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate().let { it >= from && it < from.plusWeeks(1) } }
            assertEquals(count.coerceIn(0, 200), row.getValue("trips").jsonPrimitive.int)
            assertEquals(0, row.getValue("promises_total").jsonPrimitive.int)
            assertTrue(row.getValue("minutes").jsonPrimitive.int in 0..10080)
            assertTrue(row.getValue("signal_kept").jsonPrimitive.int <= row.getValue("signal_total").jsonPrimitive.int)
            val story = row.getValue("story").jsonPrimitive.content
            assertTrue(story in setOf("", "이번 주 운전 기록을 함께 돌아보세요", "이번 주에도 쉬어 가며 운전했어요", "방향지시등을 모두 켰어요", "운전 약속을 모두 지켰어요") || Regex("이번 주 [0-9]{1,3}번 운전했어요").matches(story))
        }
        assertEquals(before, repo.saved)
        assertEquals("real-weekly", repo.report("WEEKLY")); assertEquals("real-long", repo.report("LONG"))
        assertFalse(requests.any { "report_drive_end" in it.path!! || "imports" in it.path!! })
    }
    @Test fun `ordinary mode and an ended playback make no replay requests`() = runTest {
        val sync = sync()
        presentation.set(false)
        assertFalse(sync.uploadReplay(report(), trips(), today))
        presentation.set(true)
        assertFalse(sync.uploadReplay(report(), trips(), today, canUpload = { false }))
        assertEquals(0, requests.size)
    }
    @Test fun `replay upload rejects any real or imported trip before networking`() = runTest {
        val sync = sync()
        for (source in listOf(Source.CAR, Source.TIMELINE)) {
            assertFailsWith<IllegalArgumentException> { sync.uploadReplay(report(), trips() + patternTrip().copy(source = source), today) }
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun `turning presentation off during a letter lookup stops all later requests`() = runTest {
        hook = { if (it.method == "GET" && it.requestUrl!!.encodedPath == "/rest/v1/monthly_summaries") presentation.set(false) }
        assertFalse(sync().uploadReplay(report(), trips(), today))
        assertTrue(requests.any { it.method == "GET" && "monthly_summaries" in it.path!! })
        assertFalse(requests.any { "monthly-letter" in it.path!! || "weekly_" in it.path!! || "monthly_patterns" in it.path!! })
        assertNull(state.lastSyncMs)
    }
    @Test fun `a connection change during replay lookup cannot upload to the new driver`() = runTest {
        hook = { if (it.requestUrl!!.encodedPath == "/rest/v1/car_links") state.setDriver("another-driver") }
        assertFalse(sync().uploadReplay(report(), trips(), today))
        assertEquals(1, requests.size)
        assertEquals("another-driver", state.driverId)
        assertNull(state.lastSyncMs)
    }
    @Test fun `turning presentation off on a weekly 401 prevents a retried write`() = runTest {
        var rejected = false
        response = { request ->
            if (request.requestUrl!!.encodedPath == "/rest/v1/weekly_changes" && !rejected) {
                rejected = true
                presentation.set(false)
                MockResponse().setResponseCode(401).setBody("{}")
            } else null
        }
        assertFalse(sync().uploadReplay(report(), trips(), today))
        assertTrue(rejected)
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/rest/v1/weekly_changes" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath == "/rest/v1/weekly_habits" })
        assertNull(state.lastSyncMs)
    }
    @Test fun `ending playback during session refresh prevents the next data write`() = runTest {
        val tokens = MemoryTokens(session())
        val active = AtomicBoolean(true)
        hook = { request ->
            when (request.requestUrl!!.encodedPath) {
                "/rest/v1/monthly_patterns" -> tokens.save(session(1000))
                "/auth/v1/token" -> active.set(false)
            }
        }
        assertFalse(sync(tokens).uploadReplay(report(), trips(), today, active::get))
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/auth/v1/token" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath.startsWith("/rest/v1/weekly_") })
        assertNull(state.lastSyncMs)
    }
    @Test fun `queued replay rechecks the playback after the sync lock is released`() = runTest {
        val sync = sync()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var active = true
        val blocking = launch { sync.answerDriver(1, false) { entered.complete(Unit); release.await(); false } }
        entered.await()
        val upload = async { sync.uploadReplay(report(), trips(), today, canUpload = { active }) }
        runCurrent(); active = false; release.complete(Unit); blocking.join()
        assertFalse(upload.await())
        assertTrue(requests.isEmpty())
    }
}

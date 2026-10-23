package ai.handleon.car.sync

import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.*
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class CarSyncTest {
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val prefs = MemoryPreferences()
    private val state = SyncState(prefs)
    private var mode = Mode.MAEUM
    private var presentation = false
    private var onSaved = 0
    private fun sync(client: SupabaseClient = server.client()) = CarSync(client, state, repo,
        EngineRunner(repo, onSaved = { onSaved++ }), { mode }, { presentation }) { 12345 }
    @After fun close() = server.shutdown()

    @Test fun unlinkedAndUnconfiguredNeverContactServer() = runTest {
        assertFalse(sync().syncOnce().synced)
        state.setDriver("driver")
        val disabled = SupabaseClient("", "anon", okhttp3.OkHttpClient(), MemoryTokens())
        assertFalse(sync(disabled).syncOnce().synced)
        assertEquals(0, server.requestCount)
        assertNull(state.lastSyncMs)
    }

    @Test fun removedLinkClearsDriverAndStopsBeforeImports() = runTest {
        state.setDriver("driver"); state.markSynced("driver", 1)
        server.reply("[]")
        assertFalse(sync().syncOnce().synced)
        assertNull(state.driverId)
        assertNull(state.lastSyncMs)
        assertNull(SyncState(prefs).driverId)
        assertEquals(1, server.requestCount)
    }

    @Test fun monthlySyncReadsSavedReportAndPersistsLetter() = runTest {
        state.setDriver("driver")
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]")
        server.reply("", 201); server.reply("""[{"letter":"서버 편지"}]""")
        assertTrue(sync().syncOnce().synced)
        assertEquals("서버 편지", Json.parseToJsonElement(repo.report("LETTER")!!).jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(12345L, state.lastSyncMs)
        assertEquals(12345L, SyncState(prefs).lastSyncMs)
        assertEquals(0, onSaved)
        assertEquals(4, server.requestCount)
    }

    @Test fun presentationKeepsReplayOnServerAndStillDrainsImports() = runTest {
        state.setDriver("driver"); presentation = true
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        repo.saveReport("LETTER", """{"month":"2026-10-01","text":"재생 편지"}""")
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]")
        assertFalse(sync().syncOnce().synced)
        assertEquals(listOf("/rest/v1/car_links", "/storage/v1/object/list/imports"),
            List(2) { server.takeRequest().requestUrl!!.encodedPath })
        assertEquals(2, server.requestCount)
        assertNull(state.lastSyncMs)
        assertEquals("재생 편지", Json.parseToJsonElement(repo.report("LETTER")!!).jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun importedTripsRerunEngineBeforeUploadingBuildingMonth() = runTest {
        state.setDriver("driver")
        server.reply("""[{"driver_id":"driver"}]""")
        server.reply("""[{"name":"past.json"}]""")
        server.reply("""{"version":1,"trips":[{"start":"2026-10-01T09:00:00+09:00","end":"2026-10-01T10:00:00+09:00","startLat":37.4,"startLon":127.1,"endLat":37.5,"endLon":127.2,"distanceKm":10}]}""")
        server.reply("{}"); server.reply("", 201); server.reply("[]")
        assertEquals(1, sync().syncOnce().imported)
        assertEquals(1, onSaved)
        repeat(4) { server.takeRequest() }
        val row = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
        assertEquals("building", row["stage"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        assertEquals(6, server.requestCount)
    }

    @Test fun conditionSyncOnlyUploadsDailyRows() = runTest {
        state.setDriver("driver"); mode = Mode.CONDITION
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        repo.saveReport("CONDITION", Json.encodeToString(ConditionReport(ConditionStatus.OK,
            listOf(DayResult(LocalDate.of(2026, 10, 23), 1.0, 1.0, 1.5, false)), emptyList())))
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]"); server.reply("", 201)
        sync().syncOnce()
        server.takeRequest(); server.takeRequest()
        assertEquals("/rest/v1/daily_conditions", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test fun replayNeedsPresentationAndLinkAndUsesReplaySource() = runTest {
        val sync = sync()
        sync.uploadReplay(longReport())
        presentation = true
        sync.uploadReplay(longReport())
        assertEquals(0, server.requestCount)
        state.setDriver("driver")
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("", 201); server.reply("""[{"letter":"재생 편지"}]""")
        sync.uploadReplay(longReport())
        server.takeRequest()
        val row = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
        assertEquals("replay", row["source"]!!.jsonPrimitive.content)
        assertTrue(repo.trips().isEmpty())
        assertNull(repo.report("LONG"))
    }

    @Test fun uploadFailureDoesNotAdvanceLastSync() = runTest {
        state.setDriver("driver"); state.markSynced("driver", 7)
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]"); server.reply("{}", 500)
        assertFailsWith<SupabaseException> { sync().syncOnce() }
        assertEquals(7L, state.lastSyncMs)
    }

    @Test fun savedImportWithFailedDeleteRerunsEngineOnDuplicateRetry() = runTest {
        state.setDriver("driver")
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        val file = """{"version":1,"trips":[{"start":"2026-10-01T09:00:00+09:00","end":"2026-10-01T10:00:00+09:00","startLat":37.4,"startLon":127.1,"endLat":37.5,"endLon":127.2,"distanceKm":10}]}"""
        fun enqueueImport(deleteStatus: Int) {
            server.reply("""[{"driver_id":"driver"}]""")
            server.reply("""[{"name":"past.json"}]""")
            server.reply(file); server.reply("{}", deleteStatus)
        }
        enqueueImport(500)
        assertFailsWith<SupabaseException> { sync().syncOnce() }
        assertEquals(1, repo.trips().size)
        repeat(4) { server.takeRequest() }
        enqueueImport(200); server.reply("", 201); server.reply("[]")
        // A fresh owner simulates a worker/process restart, not an in-memory retry counter.
        val resumed = CarSync(server.client(), SyncState(prefs), repo,
            EngineRunner(repo, onSaved = { onSaved++ }), { mode }, { presentation })
        assertEquals(0, resumed.syncOnce().imported)
        assertTrue(onSaved > 0)
        repeat(4) { server.takeRequest() }
        val row = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        assertEquals("building", row["stage"]!!.jsonPrimitive.content)
    }

    @Test fun relinkInConditionModeDoesNotExposePreviousDriversLetter() = runTest {
        state.setDriver("old")
        repo.saveReport("LONG", Json.encodeToString(longReport()))
        server.reply("""[{"driver_id":"old"}]"""); server.reply("[]")
        server.reply("", 201); server.reply("""[{"letter":"이전 편지"}]""")
        sync().syncOnce()
        assertNotNull(sync().savedLetter())
        // Pairing polling replaces local identity before the worker revalidates it.
        state.setDriver("new"); mode = Mode.CONDITION
        server.reply("""[{"driver_id":"new"}]"""); server.reply("[]")
        sync().syncOnce()
        assertNotNull(state.lastSyncMs)
        assertNull(sync().savedLetter())
    }
}

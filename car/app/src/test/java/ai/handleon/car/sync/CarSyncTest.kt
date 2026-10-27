package ai.handleon.car.sync

import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class CarSyncTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `driver correction rechecks permission after waiting for the sync lock`() = runTest {
        val controller = sync()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var allowed = true
        val first = launch {
            controller.answerDriver(1, false, canAnswer = { entered.complete(Unit); release.await(); false })
        }
        entered.await()
        val second = launch { controller.answerDriver(2, false, canAnswer = { allowed }) }
        runCurrent()
        allowed = false
        release.complete(Unit)
        first.join(); second.join()
        assertTrue(repo.driverStatuses().isEmpty())
        assertEquals(0, onSaved)
        assertEquals(0, server.requestCount)
    }
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val prefs = MemoryPreferences()
    private val state = SyncState(prefs)
    private var mode = Mode.MAEUM
    private var presentation = false
    private var onSaved = 0
    private val nowMs = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
    private fun sync(client: SupabaseClient = server.client()) = CarSync(client, state, repo,
        EngineRunner(repo, onSaved = { onSaved++ }), { mode }, { presentation }) { nowMs }
    @After fun close() = server.shutdown()

    @Test fun sharingStateParsesRecipients() = runTest {
        state.setDriver("driver")
        server.reply("""{"paused":true,"recipients":[{"family_name":"큰딸","scope":"trend","delay_days":3,"share_pattern":true},{"family_name":"아들","scope":"stage","delay_days":0,"share_pattern":false}]}""")
        val value = sync().sharingState()!!
        assertTrue(value.paused); assertEquals(2, value.recipients.size)
        assertEquals("큰딸 · 추이 그래프까지 · 운전 패턴 함께 · 3일 뒤 공유", value.recipients[0].description)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/get_sharing_state", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test fun pauseCallsRpcWithExplicitValue() = runTest {
        state.setDriver("driver")
        server.reply("", 204); sync().setSharingPaused(true)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/set_sharing_paused", request.path)
        assertEquals("{\"p_paused\":true}", request.body.readUtf8())
        server.reply("null"); sync().setSharingPaused(false)
        assertEquals("{\"p_paused\":false}", server.takeRequest().body.readUtf8())
    }

    @Test fun notesParsedAndMarkSeenSendsId() = runTest {
        state.setDriver("driver")
        server.reply("""[{"id":9007199254740993,"family_name":"큰딸","body":"천천히 오세요","created_at":"2026-10-25T00:00:00Z"}]""")
        val note = sync().notes()!!.single()
        assertEquals(9007199254740993L, note.id); assertEquals("큰딸", note.familyName)
        assertEquals("천천히 오세요", note.body); assertEquals("2026-10-25T00:00:00Z", note.createdAt)
        assertEquals("/rest/v1/rpc/get_car_notes", server.takeRequest().path)
        server.reply("", 204); sync().markNoteSeen(note.id)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/mark_note_seen", request.path)
        assertEquals("{\"p_id\":9007199254740993}", request.body.readUtf8())
    }

    @Test fun notConnectedDoesNothing() = runTest {
        val sync = sync()
        assertNull(sync.sharingState()); assertNull(sync.notes()); assertNull(sync.revokeAllShares())
        sync.setSharingPaused(true); sync.markNoteSeen(1)
        assertEquals(0, server.requestCount)
        state.setDriver("driver")
        val unconfigured = sync(SupabaseClient("", "", okhttp3.OkHttpClient(), MemoryTokens()))
        assertNull(unconfigured.sharingState()); assertNull(unconfigured.notes()); assertNull(unconfigured.revokeAllShares())
        unconfigured.setSharingPaused(false); unconfigured.markNoteSeen(1)
        assertEquals(0, server.requestCount)
    }

    @Test fun revokeReturnsCountAndSendsNoIdentifiers() = runTest {
        state.setDriver("driver"); server.reply("2")
        assertEquals(2, sync().revokeAllShares())
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/revoke_all_shares", request.path); assertEquals("{}", request.body.readUtf8())
    }

    @Test fun presentationModeUploadsNoCarPatterns() = runTest {
        state.setDriver("driver"); presentation = true; repo.saved += patternTrip()
        for (m in Mode.entries) {
            mode = m
            server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]")
            assertFalse(sync().syncOnce().synced)
            repeat(2) { assertFalse("monthly_patterns" in server.takeRequest().path!!) }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun replayUploadsPatternsAsReplay() = runTest {
        state.setDriver("driver"); presentation = true
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("", 201)
        server.reply("""[{"letter":"재생 편지"}]"""); server.reply("", 201)
        server.reply("", 201)
        sync().uploadReplay(longReport(), listOf(patternTrip().copy(source = Source.SIM)))
        repeat(3) { server.takeRequest() }
        val request = server.takeRequest()
        assertEquals("/rest/v1/monthly_patterns", request.requestUrl!!.encodedPath)
        val row = Json.parseToJsonElement(request.body.readUtf8()).jsonArray.single().jsonObject
        assertEquals("replay", row["source"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        assertFalse(row["partial"]!!.jsonPrimitive.boolean)
        assertEquals("/rest/v1/weekly_habits", server.takeRequest().requestUrl!!.encodedPath)
        assertTrue(repo.trips().isEmpty())
    }

    @Test fun bothModesUploadPatternsWithoutRequiringLongReport() = runTest {
        state.setDriver("driver"); repo.saved += patternTrip()
        for (m in Mode.entries) {
            mode = m
            server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]"); server.reply("", 201); server.reply("", 201)
            assertTrue(sync().syncOnce().synced)
            repeat(2) { server.takeRequest() }
            val request = server.takeRequest()
            assertEquals("/rest/v1/monthly_patterns", request.requestUrl!!.encodedPath)
            val row = Json.parseToJsonElement(request.body.readUtf8()).jsonArray.single().jsonObject
            assertEquals("car", row["source"]!!.jsonPrimitive.content)
            assertEquals(1, row["trips"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, row["usual_trips"])
            assertEquals("/rest/v1/weekly_habits", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun conditionPatternsUseSavedLongBaseline() = runTest {
        state.setDriver("driver"); mode = Mode.CONDITION
        repo.saved += patternTrip().copy(maxFromHomeKm = 8.0, endCell = 1)
        repo.saveReport("LONG", Json.encodeToString(longReport().copy(baselines =
            mapOf(FeatureId.M7 to Baseline(4.0, 1.0), FeatureId.M5 to Baseline(2.0, 1.0)))))
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("[]"); server.reply("", 201); server.reply("", 201)
        sync().syncOnce()
        repeat(2) { server.takeRequest() }
        val row = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
        assertEquals(2.0, row["radius_ratio"]!!.jsonPrimitive.double)
        assertEquals(0.5, row["places_ratio"]!!.jsonPrimitive.double)
        assertEquals("/rest/v1/weekly_habits", server.takeRequest().requestUrl!!.encodedPath)
    }

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
        assertEquals(nowMs, state.lastSyncMs)
        assertEquals(nowMs, SyncState(prefs).lastSyncMs)
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
        server.reply("{}"); server.reply("", 201); server.reply("[]"); server.reply("", 201)
        assertEquals(1, sync().syncOnce().imported)
        assertEquals(1, onSaved)
        repeat(4) { server.takeRequest() }
        val row = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray.single().jsonObject
        assertEquals("building", row["stage"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01", row["month"]!!.jsonPrimitive.content)
        assertEquals(7, server.requestCount)
    }

    @Test fun conditionWithoutTripsOnlyUploadsDailyRows() = runTest {
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
        sync.uploadReplay(longReport(), emptyList())
        presentation = true
        sync.uploadReplay(longReport(), emptyList())
        assertEquals(0, server.requestCount)
        state.setDriver("driver")
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("", 201); server.reply("""[{"letter":"재생 편지"}]""")
        server.reply("", 201); server.reply("", 201)
        sync.uploadReplay(longReport(), listOf(patternTrip().copy(source = Source.SIM)))
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
        enqueueImport(200); server.reply("", 201); server.reply("[]"); server.reply("", 201)
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

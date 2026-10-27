package ai.handleon.car.sync

import ai.handleon.car.engine.*
import ai.handleon.car.store.DriverStatus
import ai.handleon.driveprint.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class DriveNoticeSyncTest {
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val state = SyncState(MemoryPreferences()).also { it.setDriver("driver") }
    private val now = Instant.parse("2026-10-27T09:00:00Z").toEpochMilli()
    private var presentation = false
    private fun sync() = CarSync(server.client(), state, repo, EngineRunner(repo), { Mode.MAEUM }, { presentation }) { now }
    private fun trip(offset: Long = 0) = patternTrip().copy(startMs = now - 3600000 + offset, endMs = now - 1800000 + offset)
    @After fun close() = server.shutdown()
    @Test fun `only owner car outings are reported`() = runTest {
        val owner = trip()
        val pending = trip(1000)
        repo.saved.addAll(listOf(owner, pending, trip(2000).copy(source = Source.TIMELINE), trip(3000).copy(source = Source.SIM)))
        repo.setDriver(pending.startMs, DriverStatus.PENDING, null, now)
        server.reply("", 204)
        sync().reportOutings()
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/report_drive_end", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(owner.startMs.toString(), body["p_outing_key"]!!.jsonPrimitive.content)
        assertEquals(30, body["p_minutes"]!!.jsonPrimitive.int)
        assertEquals(1, server.requestCount)
    }
    @Test fun `stop notices uses one rpc with no family identifier`() = runTest {
        server.reply("2"); sync().stopDriveNotices()
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/stop_drive_notices", request.path)
        assertEquals("{}", request.body.readUtf8()); assertEquals(1, server.requestCount)
    }
    @Test fun `changed driver retracts attempted outing after restart and never reports remaining part`() = runTest {
        val a = trip(); val b = trip(35 * 60000)
        repo.saved.addAll(listOf(a, b))
        server.reply("{}", 500)
        assertFailsWith<SupabaseException> { sync().reportOutings() }
        server.takeRequest()
        repo.setDriver(a.startMs, DriverStatus.OTHER, null, now)
        server.reply("", 204)
        sync().reportOutings()
        val retract = server.takeRequest()
        assertEquals("/rest/v1/rpc/retract_drive", retract.path)
        assertEquals(a.startMs.toString(), Json.parseToJsonElement(retract.body.readUtf8()).jsonObject["p_outing_key"]!!.jsonPrimitive.content)
        sync().reportOutings()
        assertEquals(2, server.requestCount)
    }
    @Test fun `presentation mode sends no drive events`() = runTest {
        presentation = true; repo.saved += trip()
        sync().reportOutings(); sync().retractOuting(trip().startMs.toString())
        assertEquals(0, server.requestCount)
    }
    @Test fun `sharing options parse without family id`() = runTest {
        server.reply("""{"paused":false,"recipients":[{"family_name":"가족","scope":"stage","delay_days":3,"share_pattern":false,"drive_done":"evening","weekly_change":true,"habits":false}]}""")
        val sharing = sync().sharingState()!!
        assertEquals(1, sharing.driveNoticeRecipients)
        assertTrue(sharing.recipients.single().weeklyChange)
        assertFalse(sharing.recipients.single().habits)
        assertEquals(0, sharing.copy(paused = true).driveNoticeRecipients)
    }
    @Test fun `answering earlier pending owner retracts the replaced key before reporting the merged outing`() = runTest {
        val a = trip(-35 * 60000); val b = trip()
        repo.saved.addAll(listOf(a, b))
        repo.setDriver(a.startMs, DriverStatus.PENDING, null, now)
        server.reply("", 204)
        sync().reportOutings()
        assertEquals(b.startMs.toString(), Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["p_outing_key"]!!.jsonPrimitive.content)
        repo.answerDriver(a.startMs, true, now)
        server.reply("{}", 500)
        assertFailsWith<SupabaseException> { sync().reportOutings() }
        assertEquals("/rest/v1/rpc/retract_drive", server.takeRequest().path)
        server.reply("", 204); server.reply("", 204)
        sync().reportOutings()
        val retract = server.takeRequest()
        assertEquals("/rest/v1/rpc/retract_drive", retract.path)
        assertEquals(b.startMs.toString(), Json.parseToJsonElement(retract.body.readUtf8()).jsonObject["p_outing_key"]!!.jsonPrimitive.content)
        val merged = server.takeRequest()
        assertEquals("/rest/v1/rpc/report_drive_end", merged.path)
        val body = Json.parseToJsonElement(merged.body.readUtf8()).jsonObject
        assertEquals(a.startMs.toString(), body["p_outing_key"]!!.jsonPrimitive.content)
        assertEquals(60, body["p_minutes"]!!.jsonPrimitive.int)
        server.reply("", 204)
        sync().reportOutings()
        assertEquals("/rest/v1/rpc/report_drive_end", server.takeRequest().path)
    }
}

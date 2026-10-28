package ai.handleon.car.sync

import ai.handleon.car.store.DriverStatus
import ai.handleon.driveprint.Driveprint
import ai.handleon.driveprint.HolidayModel
import ai.handleon.driveprint.TripFeatures
import ai.handleon.driveprint.WeeklyTrack
import ai.handleon.driveprint.Source
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class FedClientTest {
    private val server = MockWebServer()
    private val repo = MemoryTrips()
    private val consentPrefs = MemoryPreferences()
    private val zone = ZoneId.of("Asia/Seoul")
    private val monday = ZonedDateTime.parse("2026-10-26T04:00:00+09:00").toInstant().toEpochMilli()
    private var presentation = false
    private val linked = SyncState(MemoryPreferences()).apply { setDriver("driver") }
    private var driver: String?
        get() = linked.driverId
        set(value) { linked.setDriver(value) }
    private var available = true
    private fun countTrips(weeks: Int = 5) = (0 until weeks).flatMap { week ->
        List(week + 1) { index ->
            val start = LocalDate.of(2026, 9, 14).plusWeeks(week.toLong()).atTime(9, index).atZone(zone).toInstant().toEpochMilli()
            patternTrip().copy(startMs = start, endMs = start + 1000)
        }
    }
    private fun model(round: Int = 0, weights: List<Double> = List(15) { 0.0 }) = buildJsonObject {
        put("round", round); put("weights", JsonArray(weights.map(::JsonPrimitive)))
    }.toString()
    private fun client(virtual: suspend () -> List<FedVirtual> = { emptyList() }) = FedClient(server.client(), repo,
        { presentation }, FedConsentStore(consentPrefs), { driver }, virtual, { available }, { linked.connectionGeneration })
    private suspend fun enable(fed: FedClient) {
        server.reply("null"); fed.setOptIn(true)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/set_fed_opt_in", request.path)
        assertEquals("{\"p_on\":true}", request.body.readUtf8())
    }
    @After fun close() = server.shutdown()

    @Test fun `opted out sends nothing`() = runTest {
        repo.saved += countTrips()
        val fed = client()
        fed.load()
        assertFalse(fed.state.value.enabled)
        assertEquals(0, fed.round(monday))
        assertEquals(0, server.requestCount)
    }
    @Test fun `ratios use only completed car weeks`() {
        val start = LocalDate.of(2026, 10, 19).atStartOfDay(zone).toInstant().toEpochMilli()
        val t = patternTrip().copy(startMs = start, endMs = start + 1000)
        val current = t.copy(startMs = monday, endMs = monday + 1000)
        val futureEnd = t.copy(endMs = monday + 1000)
        val inputs = listOf(t, t.copy(startMs = start + 2000, endMs = start + 3000), t.copy(source = Source.SIM),
            t.copy(source = Source.TIMELINE), current, futureEnd)
        assertEquals(listOf(LocalDate.of(2026, 10, 19) to 2.0), weeklyRatios(inputs, zone, monday))
        // UTC still sees Sunday immediately before KST Monday begins.
        val boundary = ZonedDateTime.parse("2026-10-25T15:00:00Z").toInstant().toEpochMilli()
        val sunday = t.copy(startMs = boundary - 3600000, endMs = boundary - 3500000)
        assertEquals(1, weeklyRatios(listOf(sunday), zone, boundary).size)
        assertTrue(weeklyRatios(listOf(sunday), ZoneId.of("UTC"), boundary).isEmpty())
    }
    @Test fun `self sends only the exact clipped engine delta and sample count`() = runTest {
        repo.saved += countTrips()
        repo.drivers[repo.saved.last().startMs] = DriverStatus.OTHER
        val fed = client(); enable(fed)
        server.reply(model()); server.reply("null")
        assertEquals(1, fed.round(monday))
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/submit_fed_update", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("p_round", "p_client_tag", "p_delta", "p_n"), body.keys)
        val weeks = weeklyRatios(repo.trips(), zone, monday)
        assertEquals(weeks.size, body.getValue("p_n").jsonPrimitive.int)
        assertEquals("self", body.getValue("p_client_tag").jsonPrimitive.content)
        assertContentEquals(HolidayModel.localDelta(DoubleArray(15), weeks), body.getValue("p_delta").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray())
        assertEquals(model(), repo.report("FED_MODEL"))
    }
    @Test fun `presentation adds two virtual clients`() = runTest {
        val virtual = parseFedVirtuals(File("src/main/assets/fed_virtual_weeks.json").readText())
        assertEquals(listOf("virtual-1", "virtual-2"), virtual.map { it.tag })
        repo.saved += countTrips(); presentation = true
        val fed = client { virtual }; enable(fed)
        server.reply(model()); repeat(3) { server.reply("null") }
        assertEquals(3, fed.round(monday))
        server.takeRequest()
        val bodies = List(3) { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
        assertEquals(listOf("self", "virtual-1", "virtual-2"), bodies.map { it.getValue("p_client_tag").jsonPrimitive.content })
        bodies.forEach { body ->
            assertEquals(setOf("p_round", "p_client_tag", "p_delta", "p_n"), body.keys)
            assertTrue(body.getValue("p_n").jsonPrimitive.int in 4..520)
            assertTrue(body.getValue("p_delta").jsonArray.all { it.jsonPrimitive.double.isFinite() && it.jsonPrimitive.double in -1.0..1.0 })
        }
    }
    @Test fun `ordinary mode never loads or submits virtual records`() = runTest {
        repo.saved += countTrips()
        val fed = client { error("must not load virtual data") }; enable(fed)
        server.reply(model()); server.reply("null")
        assertEquals(1, fed.round(monday))
    }
    @Test fun `short histories do not submit an empty update and training is limited to 520 weeks`() = runTest {
        repo.saved += countTrips(3)
        val fed = client(); enable(fed)
        server.reply(model()); assertEquals(0, fed.round(monday))
        assertEquals(2, server.requestCount)
        val old = LocalDate.of(2000, 1, 3)
        val many = (0L..599L).map { i ->
            val t = old.plusWeeks(i).atStartOfDay(zone).toInstant().toEpochMilli()
            patternTrip().copy(startMs = t, endMs = t + 1000)
        }
        assertEquals(520, weeklyRatios(many, zone, monday).size)
        assertEquals(old.plusWeeks(80), weeklyRatios(many, zone, monday).first().first)
    }
    @Test fun `every toggle calls the RPC and failed opt out blocks further submissions`() = runTest {
        val fed = client(); enable(fed)
        server.reply("{}", 503)
        assertFailsWith<SupabaseException> { fed.setOptIn(false) }
        assertFalse(fed.state.value.enabled)
        assertTrue(fed.state.value.pendingOff)
        assertEquals("{\"p_on\":false}", server.takeRequest().body.readUtf8())
        assertEquals(0, fed.round(monday))
        assertEquals(2, server.requestCount)
        server.reply("null"); server.reply(model(1))
        fed.refreshModel()
        assertEquals("/rest/v1/rpc/set_fed_opt_in", server.takeRequest().path)
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        assertFalse(fed.state.value.pendingOff)
    }
    @Test fun `failed opt in never enables participation and choices do not cross drivers`() = runTest {
        val fed = client(); server.reply("{}", 503)
        assertFailsWith<SupabaseException> { fed.setOptIn(true) }
        assertFalse(fed.state.value.enabled); server.takeRequest()
        enable(fed)
        driver = "another-driver"
        fed.load()
        assertFalse(fed.state.value.enabled)
        val before = server.requestCount
        assertEquals(0, fed.round(monday))
        assertEquals(before, server.requestCount)
    }
    @Test fun `model refresh works while opted out and failed fetch disables cached effect`() = runTest {
        val fed = client()
        val weights = List(15) { if (it == 0) -0.3 else 0.0 }
        server.reply(model(2, weights)); fed.refreshModel()
        assertNotNull(fedEffect(repo.report("FED_MODEL")))
        assertFalse(fed.state.value.enabled)
        server.reply("{}", 503)
        assertFailsWith<SupabaseException> { fed.refreshModel() }
        assertNull(fedEffect(repo.report("FED_MODEL")))
        assertNull(fed.state.value.modelRound)
    }
    @Test fun `round zero and invalid global models never produce an effect`() {
        for (raw in listOf(null, "{}", model(), model(0, List(15) { 0.2 }), model(-1), model(2, List(14) { 0.0 }),
            """{"round":1,"weights":["NaN"]}""")) assertNull(fedEffect(raw))
        assertEquals(-0.3, fedEffect(model(1, List(15) { if (it == 2) -0.3 else 0.0 }))!!(LocalDate.of(2026, 9, 21)))
    }
    @Test fun `an intercept-only global model never holds an ordinary week`() {
        // The shared server once held round 2 = [0.5, 0, -0.025, 0...] from integration tests.
        val effect = fedEffect(model(2, List(15) { when (it) { 0 -> 0.5; 2 -> -0.025; else -> 0.0 } }))!!
        assertEquals(0.0, effect(LocalDate.of(2026, 10, 19)))
        val week = LocalDate.of(2026, 10, 19)
        val trips = (0L..3L).map { week.plusDays(it) }.map { day ->
            val start = day.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            TripFeatures(start, start + 1_800_000, Source.CAR, 15.0, false, false, false, null, null, false, false, 0.0,
                0, 0, 0, 0, 0, null, 0, null, null, 0, 0)
        }
        val report = Driveprint().weeklyReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 10, 28), effect = effect)
        assertEquals(false, report.weeks.single().excluded)
        assertEquals(WeeklyTrack.MIN_BASE - 1, report.weeksUntilReady)
    }
    @Test fun `round change is not retried against a new model in the same attempt`() = runTest {
        repo.saved += countTrips(); presentation = true
        val fed = client { listOf(FedVirtual("virtual-1", emptyList()), FedVirtual("virtual-2", emptyList())) }; enable(fed)
        server.reply(model(1)); server.reply("""{"code":"P0001","message":"round_changed"}""", 400)
        assertEquals("round_changed", assertFailsWith<SupabaseException> { fed.round(monday) }.code)
        assertEquals(3, server.requestCount)
    }
    @Test fun `server opt out is respected without silently enabling again`() = runTest {
        repo.saved += countTrips(); val fed = client(); enable(fed)
        server.reply(model(1)); server.reply("""{"code":"P0001","message":"fed_opt_in_required"}""", 400)
        assertFailsWith<SupabaseException> { fed.round(monday) }
        assertFalse(fed.state.value.enabled)
        assertEquals(0, fed.round(monday))
        assertEquals(3, server.requestCount)
    }
    @Test fun `daily refresh submits only once on KST Monday and survives client recreation`() = runTest {
        repo.saved += countTrips(); val fed = client(); enable(fed)
        server.reply(model()); server.reply("null")
        assertEquals(1, fed.daily(monday))
        repeat(2) { server.takeRequest() }
        val restarted = client()
        server.reply(model(1)); assertEquals(0, restarted.daily(monday + 1000))
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        server.reply(model(1)); assertEquals(0, restarted.daily(monday + 86400000))
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
    }
    @Test fun `switching presentation off while virtual data loads prevents virtual submissions`() = runTest {
        repo.saved += countTrips(); presentation = true
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val weeks = weeklyRatios(repo.saved, zone, monday)
        val fed = client { entered.complete(Unit); resume.await(); listOf(FedVirtual("virtual-1", weeks), FedVirtual("virtual-2", weeks)) }
        enable(fed); server.reply(model()); server.reply("null")
        val round = async { fed.round(monday) }
        entered.await(); presentation = false; resume.complete(Unit)
        assertEquals(1, round.await())
        assertEquals(3, server.requestCount)
    }
    @Test fun `queued opt out survives screen cancellation and prevents the next daily upload`() = runTest {
        repo.saved += countTrips(); presentation = true
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val fed = client {
            entered.complete(Unit); resume.await()
            listOf(FedVirtual("virtual-1", emptyList()), FedVirtual("virtual-2", emptyList()))
        }
        enable(fed); server.reply(model()); server.reply("null")
        val active = async { fed.round(monday) }
        entered.await()
        val off = async(start = CoroutineStart.UNDISPATCHED) { fed.setOptIn(false) }
        off.cancelAndJoin()
        resume.complete(Unit); active.await()
        repeat(2) { server.takeRequest() }
        val restarted = client()
        restarted.load()
        assertFalse(restarted.state.value.enabled)
        assertTrue(restarted.state.value.pendingOff)
        server.reply("null"); server.reply(model())
        assertEquals(0, restarted.daily(monday))
        assertEquals("{\"p_on\":false}", server.takeRequest().body.readUtf8())
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        assertFalse(restarted.state.value.pendingOff)
    }
    @Test fun `queued toggle is discarded when the linked driver changes`() = runTest {
        repo.saved += countTrips(); presentation = true
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val fed = client {
            entered.complete(Unit); resume.await()
            listOf(FedVirtual("virtual-1", emptyList()), FedVirtual("virtual-2", emptyList()))
        }
        enable(fed); server.reply(model()); server.reply("null")
        val active = async { fed.round(monday) }
        entered.await()
        val toggle = async(start = CoroutineStart.UNDISPATCHED) { fed.setOptIn(true) }
        driver = "another-driver"
        server.reply("null") // Old implementation sends the stale toggle to the new driver.
        resume.complete(Unit); active.await()
        assertFailsWith<CancellationException> { toggle.await() }
        fed.load()
        assertFalse(fed.state.value.enabled)
        assertEquals(3, server.requestCount)
    }
    @Test fun `failed withdrawal survives collected data erasure and client recreation`() = runTest {
        val fed = client(); enable(fed)
        server.reply("{}", 503)
        assertFailsWith<SupabaseException> { fed.setOptIn(false) }
        server.takeRequest()
        fed.withErasure { repo.reports.clear(); repo.saved.clear() }
        assertTrue(repo.reports.isEmpty())
        val restarted = client(); restarted.load()
        assertFalse(restarted.state.value.enabled)
        assertTrue(restarted.state.value.pendingOff)
        server.reply("null"); server.reply(model())
        restarted.refreshModel()
        assertEquals("{\"p_on\":false}", server.takeRequest().body.readUtf8())
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        assertFalse(restarted.state.value.pendingOff)
    }
    @Test fun `returning to the same driver does not revive a queued toggle`() = runTest {
        val fed = client()
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val erasure = async { fed.withErasure { entered.complete(Unit); resume.await() } }
        entered.await()
        val toggle = async(start = CoroutineStart.UNDISPATCHED) { fed.setOptIn(true) }
        driver = "another-driver"; driver = "driver"
        resume.complete(Unit); erasure.await()
        assertFailsWith<CancellationException> { toggle.await() }
        fed.load()
        assertFalse(fed.state.value.enabled)
        assertEquals(0, server.requestCount)
    }
    @Test fun `pending withdrawal is retried only for its original driver after relinking`() = runTest {
        val fed = client(); enable(fed)
        server.reply("{}", 503)
        assertFailsWith<SupabaseException> { fed.setOptIn(false) }
        server.takeRequest()
        driver = "another-driver"
        server.reply(model()); fed.refreshModel()
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        assertFalse(fed.state.value.pendingOff)
        fed.withErasure { repo.reports.clear() }
        driver = "driver"
        val restarted = client()
        server.reply("null"); server.reply(model()); restarted.refreshModel()
        assertEquals("{\"p_on\":false}", server.takeRequest().body.readUtf8())
        assertEquals("/rest/v1/rpc/get_fed_model", server.takeRequest().path)
        assertFalse(restarted.state.value.pendingOff)
    }
    @Test fun `local erasure resets enabled consent without restoring it on restart`() = runTest {
        val fed = client(); enable(fed)
        fed.withErasure { repo.reports.clear() }
        val restarted = client(); restarted.load()
        assertFalse(restarted.state.value.enabled)
        assertFalse(restarted.state.value.pendingOff)
        assertEquals(0, restarted.round(monday))
        assertEquals(1, server.requestCount)
    }
}

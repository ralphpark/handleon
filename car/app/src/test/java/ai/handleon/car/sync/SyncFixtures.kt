package ai.handleon.car.sync

import ai.handleon.car.store.TripStore
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

internal class MemoryTokens(var session: Session? = null) : TokenStore {
    override fun load() = session
    override fun save(session: Session?) { this.session = session }
}

internal fun session(expires: Long = 10_000) = Session("car-id", "access-secret", "refresh-secret", expires)
internal fun MockWebServer.reply(body: String, status: Int = 200) = enqueue(MockResponse().setResponseCode(status).setBody(body))
internal fun MockWebServer.client(tokens: TokenStore = MemoryTokens(session())) = SupabaseClient(
    url("/").toString(), "anon-secret", OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(), tokens) { 1000 }
internal fun auth(access: String = "new-access", refresh: String = "new-refresh") =
    """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"user":{"id":"car-id"}}"""
internal fun longReport() = LongReport(Stage.TALK,
    listOf(MonthResult(YearMonth.of(2026, 8), mapOf(Domain.MOBILITY to 1.0), emptySet()),
        MonthResult(YearMonth.of(2026, 9), mapOf(Domain.MOBILITY to 2.0, Domain.WAYFINDING to 3.0), setOf(Domain.MOBILITY, Domain.WAYFINDING))),
    emptyMap(), emptyMap(), listOf(Contribution(FeatureId.M1, -2.0, 2.0), Contribution(FeatureId.W3, 3.0, 0.9)), null)

internal class MemoryTrips : TripStore {
    val saved = mutableListOf<TripFeatures>()
    val keys = mutableSetOf<String>()
    val reports = mutableMapOf<String, String>()
    var failSave = false
    override suspend fun saveTrip(f: TripFeatures, importKey: String?): Boolean {
        if (failSave) error("storage unavailable")
        if (importKey != null && !keys.add(importKey)) return false
        saved += f
        return true
    }
    override suspend fun trips() = saved.toList()
    override suspend fun labels() = emptyMap<LocalDate, String>()
    override suspend fun setLabel(day: LocalDate, kind: String?) = Unit
    override suspend fun resets() = emptyList<Pair<LocalDate, String>>()
    override suspend fun addReset(day: LocalDate, kind: String) = Unit
    override suspend fun home() = LatLon(37.4, 127.1)
    override suspend fun familiarCells() = setOf(Geo.cell(LatLon(37.395, 127.111)))
    override suspend fun setPlaces(home: LatLon?, familiar: List<LatLon>) = Unit
    override suspend fun saveReport(kind: String, json: String) { reports[kind] = json }
    override suspend fun report(kind: String) = reports[kind]
}

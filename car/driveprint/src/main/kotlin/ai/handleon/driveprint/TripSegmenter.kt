package ai.handleon.driveprint

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Feed chronological vehicle samples; asynchronous events are joined by timestamp. */
class TripSegmenter {
    private val samples = mutableListOf<Sample>()
    private val navi = mutableListOf<NaviEvent>()
    private val telltales = mutableListOf<TelltaleOn>()
    private var parkedSince: Long? = null

    @Serializable
    private data class Checkpoint(val samples: List<Sample>, val navi: List<NaviEvent>,
        val telltales: List<TelltaleOn>, val parkedSince: Long?)

    fun snapshot(): String = Json.encodeToString(Checkpoint(samples, navi, telltales, parkedSince))

    fun restore(json: String) {
        val state = Json.decodeFromString<Checkpoint>(json)
        require(state.samples.zipWithNext().all { (a, b) -> a.tMs <= b.tMs }) { "Samples must be chronological" }
        val parked = state.samples.takeLastWhile { it.gear == Gear.P }.firstOrNull()?.tMs
        require(state.parkedSince == parked) { "Invalid parking checkpoint" }
        samples.clear(); samples.addAll(state.samples)
        navi.clear(); navi.addAll(state.navi)
        telltales.clear(); telltales.addAll(state.telltales)
        parkedSince = state.parkedSince
    }

    fun push(s: Sample): RawTrip? {
        val previous = samples.lastOrNull()
        require(previous == null || s.tMs >= previous.tMs) { "Samples must be chronological" }
        val closed = if (previous != null && s.tMs - previous.tMs > 300_000) close() else null

        if (samples.isEmpty()) {
            if (s.gear != Gear.D && s.gear != Gear.R) return closed
            navi.removeAll { it.tMs < s.tMs }
            telltales.removeAll { it.tMs < s.tMs }
        }
        samples.add(s)
        if (s.gear == Gear.P) {
            val since = parkedSince ?: s.tMs.also { parkedSince = it }
            if (s.tMs - since >= 30_000) return close()
        } else {
            parkedSince = null
        }
        return closed
    }

    fun pushNavi(e: NaviEvent) { navi.add(e) }
    fun pushTelltale(e: TelltaleOn) { telltales.add(e) }
    fun flush(): RawTrip? = close()

    /** 진행 중인 트립의 사본(발표 모드 엔진 화면용). 상태를 바꾸지 않고, 길이·거리 조건도 따지지 않는다. */
    fun current(): RawTrip? {
        if (samples.isEmpty()) return null
        val start = samples.first().tMs
        return RawTrip(samples.toList(), navi.filter { it.tMs >= start }.sortedBy { it.tMs },
            telltales.filter { it.tMs >= start }.sortedBy { it.tMs })
    }

    private fun close(): RawTrip? {
        if (samples.isEmpty()) return null
        val start = samples.first().tMs
        val end = samples.last().tMs
        val trip = RawTrip(
            samples.toList(),
            navi.filter { it.tMs in start..end }.sortedBy { it.tMs },
            telltales.filter { it.tMs in start..end }.sortedBy { it.tMs },
        )
        samples.clear()
        parkedSince = null
        navi.removeAll { it.tMs <= end }
        telltales.removeAll { it.tMs <= end }
        return trip.takeIf { end - start >= 60_000 && it.distanceKm() >= 0.3 }
    }
}

/** Left-endpoint speed integration, never extrapolating across more than two seconds. */
fun RawTrip.distanceKm(): Double = samples.zipWithNext().sumOf { (a, b) ->
    val speed = a.speedKmh?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    speed * (b.tMs - a.tMs).coerceIn(0, 2_000) / 3_600_000.0
}

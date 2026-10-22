package ai.handleon.driveprint

/** Feed chronological vehicle samples; asynchronous events are joined by timestamp. */
class TripSegmenter {
    private val samples = mutableListOf<Sample>()
    private val navi = mutableListOf<NaviEvent>()
    private val telltales = mutableListOf<TelltaleOn>()
    private var parkedSince: Long? = null

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

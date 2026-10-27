package ai.handleon.driveprint

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

object Features {
    fun extract(trip: RawTrip, home: LatLon?, familiar: Set<Long>, zone: ZoneId): TripFeatures {
        require(trip.samples.isNotEmpty()) { "A trip needs at least one sample" }
        val samples = trip.samples
        val first = samples.first()
        val last = samples.last()
        val events = trip.navi.filter { it.tMs in first.tMs..last.tMs }.sortedBy { it.tMs }
        val telltales = trip.telltales.filter { it.tMs in first.tMs..last.tMs }
        val positions = samples.mapNotNull { it.pos }
        val endCell = positions.lastOrNull()?.let(Geo::cell)
        val wipers = samples.mapNotNull { it.wiperOn }
        val turns = detectTurns(samples, events)
        val (announced, missed) = matchAnnouncedTurns(turns, events, samples)
        val (steeringStd, reversals) = straightWindows(samples)
        return TripFeatures(
            startMs = first.tMs, endMs = last.tMs, source = Source.CAR,
            distanceKm = trip.distanceKm(),
            night = night(first.pos, first.tMs, zone),
            rain = wipers.isNotEmpty() && wipers.count { it }.toDouble() / wipers.size >= 0.2,
            offDay = KoreanHolidays.isOffDay(Instant.ofEpochMilli(first.tMs).atZone(zone).toLocalDate()),
            endCell = endCell,
            maxFromHomeKm = home?.let { h -> positions.maxOfOrNull { Geo.haversineKm(h, it) } },
            familiarDest = endCell != null && endCell in familiar,
            guided = events.any { it is GuidanceStarted },
            guidedKm = guidedDistance(samples, events),
            reroutes = events.count { it is Reroute },
            leftTurns = turns.count { it.direction == Signal.LEFT },
            rightTurns = turns.count { it.direction == Signal.RIGHT },
            announcedTurns = announced, signalMisses = missed,
            speedingRatio = speedingRatio(samples),
            hardBrakes = hardBrakes(samples),
            steeringStd = steeringStd, steeringReversalsPerMin = reversals,
            lkaEvents = telltales.count { it.type == "LKA" },
            fcaEvents = telltales.count { it.type == "FCA" },
            hardAccels = hardAccels(samples),
        )
    }

    /** Timeline records only provide endpoints, so the home radius is an endpoint lower bound. */
    fun fromPastTrip(
        startMs: Long, endMs: Long, start: LatLon, end: LatLon, distanceKm: Double,
        home: LatLon?, familiar: Set<Long>, zone: ZoneId,
    ): TripFeatures {
        val endCell = Geo.cell(end)
        return TripFeatures(
            startMs = startMs, endMs = endMs, source = Source.TIMELINE, distanceKm = distanceKm,
            night = Sun.isNight(start, startMs, zone), rain = false,
            offDay = KoreanHolidays.isOffDay(Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()),
            endCell = endCell,
            maxFromHomeKm = home?.let { maxOf(Geo.haversineKm(it, start), Geo.haversineKm(it, end)) },
            familiarDest = endCell in familiar, guided = false, guidedKm = 0.0, reroutes = 0,
            leftTurns = 0, rightTurns = 0, announcedTurns = 0, signalMisses = 0,
            speedingRatio = null, hardBrakes = 0, steeringStd = null,
            steeringReversalsPerMin = null, lkaEvents = 0, fcaEvents = 0,
        )
    }

    private fun night(pos: LatLon?, tMs: Long, zone: ZoneId): Boolean =
        if (pos != null) Sun.isNight(pos, tMs, zone)
        else Instant.ofEpochMilli(tMs).atZone(zone).hour.let { it >= 18 || it < 6 }

    private data class Turn(val startMs: Long, val endMs: Long, val direction: Signal)
    private data class HeadingStep(val startMs: Long, val delta: Double)

    private fun detectTurns(samples: List<Sample>, events: List<NaviEvent>): List<Turn> {
        if (samples.none { it.headingDeg != null }) {
            return events.filterIsInstance<TurnPassed>()
                .filter { it.direction == Signal.LEFT || it.direction == Signal.RIGHT }
                .distinctBy { it.tMs to it.direction }
                .map { Turn(it.tMs - 10_000, it.tMs, it.direction) }
        }
        val turns = mutableListOf<Turn>()
        val window = ArrayDeque<HeadingStep>()
        for ((a, b) in samples.zipWithNext()) {
            if (a.headingDeg == null || b.headingDeg == null ||
                a.speedKmh == null || b.speedKmh == null || a.speedKmh < 5 || b.speedKmh < 5 ||
                b.tMs - a.tMs !in 1..2_000) {
                window.clear()
                continue
            }
            val delta = Geo.headingDelta(a.headingDeg, b.headingDeg)
            if (abs(delta) > 1e-9) window.addLast(HeadingStep(a.tMs, delta))
            while (window.isNotEmpty() && b.tMs - window.first().startMs > 20_000) window.removeFirst()
            val accumulated = window.sumOf { it.delta }
            if (abs(accumulated) >= 60) {
                turns.add(Turn(window.first().startMs, b.tMs, if (accumulated > 0) Signal.RIGHT else Signal.LEFT))
                window.clear()
            }
        }
        return turns
    }

    private fun matchAnnouncedTurns(
        turns: List<Turn>, events: List<NaviEvent>, samples: List<Sample>,
    ): Pair<Int, Int> {
        if (samples.none { it.signal != null }) return 0 to 0
        val ahead = events.filterIsInstance<TurnAhead>()
            .filter { it.distanceM in 0.0..300.0 && (it.direction == Signal.LEFT || it.direction == Signal.RIGHT) }
            .toMutableList()
        var announced = 0
        var missed = 0
        for (turn in turns) {
            val matched = ahead.any {
                it.direction == turn.direction && turn.endMs - it.tMs in 0..60_000
            }
            if (!matched) continue
            announced++
            val signalled = samples.any {
                it.tMs >= turn.startMs - 15_000 && it.tMs <= turn.endMs &&
                    (it.signal == turn.direction || it.signal == Signal.HAZARD)
            }
            if (!signalled) missed++
            // TBT updates for the same already-performed turn are one announcement.
            ahead.removeAll { it.direction == turn.direction && it.tMs <= turn.endMs }
        }
        return announced to missed
    }

    private fun guidedDistance(samples: List<Sample>, events: List<NaviEvent>): Double {
        val intervals = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        for (event in events) {
            when (event) {
                is GuidanceStarted -> if (start == null) start = event.tMs
                is GuidanceEnded -> {
                    start?.let { intervals.add(it to event.tMs) }
                    start = null
                }
                else -> Unit
            }
        }
        start?.let { intervals.add(it to samples.last().tMs) }
        return samples.zipWithNext().sumOf { (a, b) ->
            val speed = a.speedKmh?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            val end = minOf(b.tMs, a.tMs + 2_000)
            val coveredMs = intervals.sumOf { (from, to) ->
                (minOf(end, to) - maxOf(a.tMs, from)).coerceAtLeast(0)
            }
            speed * coveredMs / 3_600_000.0
        }
    }

    private fun speedingRatio(samples: List<Sample>): Double? {
        var knownMs = 0L
        var speedingMs = 0L
        samples.forEachIndexed { i, sample ->
            val limit = sample.speedLimitKmh
            val speed = sample.speedKmh
            if (limit != null && limit > 0 && speed != null && speed.isFinite() && speed > 0) {
                // Input is a 1 Hz observation stream; the final sample represents its second.
                val duration = if (i == samples.lastIndex) 1_000L
                    else (samples[i + 1].tMs - sample.tMs).coerceIn(0, 2_000)
                knownMs += duration
                if (speed >= limit + 10.0) speedingMs += duration
            }
        }
        return if (knownMs < 60_000) null else speedingMs.toDouble() / knownMs
    }

    private fun hardAccels(samples: List<Sample>): Int = samples.zipWithNext().count { (a, b) ->
        val previous = a.speedKmh
        val current = b.speedKmh
        val dt = b.tMs - a.tMs
        previous != null && current != null && previous.isFinite() && current.isFinite() &&
            dt in 1..2_000 && (current - previous) * 1_000 / dt + 1e-9 >= 12.9
    }

    private fun hardBrakes(samples: List<Sample>): Int {
        val brakeAvailable = samples.any { it.brake != null }
        var lastEvent: Long? = null
        var count = 0
        for ((a, b) in samples.zipWithNext()) {
            val previous = a.speedKmh ?: continue
            val current = b.speedKmh ?: continue
            val dt = b.tMs - a.tMs
            if (dt !in 1..2_000) continue
            val perSecond = (previous - current) * 1_000 / dt
            if (previous >= 19 && perSecond >= 12.9 && (!brakeAvailable || b.brake == true) &&
                (lastEvent == null || b.tMs - lastEvent > 3_000)) {
                count++
                lastEvent = b.tMs
            }
        }
        return count
    }

    /** Disjoint 10-second windows avoid counting the same exposure repeatedly. */
    private fun straightWindows(samples: List<Sample>): Pair<Double?, Double?> {
        val deviations = mutableListOf<Double>()
        var reversals = 0
        var index = 0
        while (index + 10 <= samples.size) {
            val window = samples.subList(index, index + 10)
            val speed = window.mapNotNull { it.speedKmh?.takeIf(Double::isFinite) }
            val heading = window.mapNotNull { it.headingDeg?.takeIf(Double::isFinite) }
            val steering = window.mapNotNull { it.steeringDeg?.takeIf(Double::isFinite) }
            val contiguous = window.zipWithNext().all { (a, b) -> b.tMs - a.tMs in 800L..1_200L }
            val straight = if (heading.size == 10)
                abs(Geo.headingDelta(heading.first(), heading.last())) <= 10
                else steering.size == 10 && steering.all { abs(it) <= 15 }
            val valid = contiguous && speed.size == 10 && steering.size == 10 &&
                speed.min() >= 40 && speed.max() - speed.min() <= 5 && straight
            if (!valid) {
                index++
                continue
            }
            val mean = steering.average()
            deviations.add(sqrt(steering.sumOf { (it - mean) * (it - mean) } / steering.size))
            val signs = steering.zipWithNext().map { (a, b) -> b - a }
                .filter { abs(it) >= 0.5 }.map { it.sign }
            reversals += signs.zipWithNext().count { (a, b) -> a != b }
            index += 10
        }
        val seconds = deviations.size * 10
        return if (seconds < 60) null to null
            else deviations.average() to reversals * 60.0 / seconds
    }
}

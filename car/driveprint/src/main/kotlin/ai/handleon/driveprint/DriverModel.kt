package ai.handleon.driveprint

import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

@Serializable data class SeatPose(val foreAft: Int, val height: Int, val backrest: Int)
@Serializable data class StyleSample(val meanSpeedKmh: Double, val accelP90: Double, val decelP90: Double,
                                     val brakesPerMin: Double, val steeringStd: Double?)
@Serializable data class DriverObservation(val seat: SeatPose?, val style: StyleSample?)
enum class DriverCall { OWNER, OTHER, ASK }

@Serializable
data class DriverModel(val owner: List<DriverObservation> = emptyList(),
                       val others: List<DriverObservation> = emptyList()) {
    fun learn(o: DriverObservation, isOwner: Boolean): DriverModel =
        if (isOwner) copy(owner = (owner + o).takeLast(50)) else copy(others = (others + o).takeLast(50))

    fun ownerProbability(o: DriverObservation): Double? {
        if (owner.size < MIN_OWNER) return null
        val own = owner.map { it.values() }
        val other = others.map { it.values() }
        var logOdds = 0.0 // Equal class priors; accumulate likelihood ratios in log space.
        var used = 0
        o.values().forEachIndexed { i, value ->
            if (value == null) return@forEachIndexed
            val a = distribution(own.mapNotNull { it[i] }) ?: return@forEachIndexed
            val b = distribution(other.mapNotNull { it[i] }) ?: a.copy(variance = a.variance * 9)
            logOdds += a.logLikelihood(value) - b.logLikelihood(value)
            used++
        }
        if (used == 0 || logOdds.isNaN()) return null
        return if (logOdds >= 0) 1 / (1 + exp(-logOdds)) else exp(logOdds) / (1 + exp(logOdds))
    }

    fun call(o: DriverObservation): DriverCall {
        if (owner.size < MIN_OWNER) {
            val seat = o.seat ?: return DriverCall.OWNER
            val seats = owner.mapNotNull { it.seat }
            if (seats.isEmpty()) return DriverCall.OWNER
            val moved = abs(seat.foreAft - Stats.median(seats.map { it.foreAft.toDouble() })) > SEAT_TOLERANCE ||
                abs(seat.height - Stats.median(seats.map { it.height.toDouble() })) > SEAT_TOLERANCE ||
                abs(seat.backrest - Stats.median(seats.map { it.backrest.toDouble() })) > SEAT_TOLERANCE
            return if (moved) DriverCall.ASK else DriverCall.OWNER
        }
        val probability = ownerProbability(o) ?: return DriverCall.OWNER
        return when {
            probability >= .8 -> DriverCall.OWNER
            probability <= .3 -> DriverCall.OTHER
            else -> DriverCall.ASK
        }
    }

    private data class Gaussian(val mean: Double, val variance: Double) {
        // The common -log(2*pi)/2 term cancels in the likelihood ratio.
        fun logLikelihood(x: Double): Double = -.5 * (ln(variance) + (x - mean) * (x - mean) / variance)
    }

    private fun distribution(values: List<Double>): Gaussian? {
        if (values.isEmpty()) return null
        val mean = values.average()
        // Plan's one-percent feature-magnitude floor; zero-valued features still need positive variance.
        val variance = maxOf(values.sumOf { (it - mean) * (it - mean) } / values.size, abs(mean) * .01, .01)
        return if (mean.isFinite() && variance.isFinite()) Gaussian(mean, variance) else null
    }

    companion object {
        const val MIN_OWNER = 10
        const val SEAT_TOLERANCE = 15
    }
}

private fun DriverObservation.values(): List<Double?> = listOf(
    seat?.foreAft?.toDouble(), seat?.height?.toDouble(), seat?.backrest?.toDouble(),
    style?.meanSpeedKmh, style?.accelP90, style?.decelP90, style?.brakesPerMin, style?.steeringStd,
).map { it?.takeIf(Double::isFinite) }

object Style {
    /** First elapsed window; acceleration percentiles are in km/h per second. */
    fun firstMinutes(samples: List<Sample>, minutes: Int = 5): StyleSample? {
        if (samples.isEmpty() || minutes <= 0) return null
        val ordered = samples.sortedBy { it.tMs }
        val end = ordered.first().tMs + minutes * 60_000L
        val window = ordered.takeWhile { it.tMs < end }
        val moving = window.mapNotNull { it.speedKmh?.takeIf { speed -> speed.isFinite() && speed > 0 } }
        if (moving.size < 60) return null
        val deltas = window.zipWithNext().mapNotNull { (a, b) ->
            val before = a.speedKmh?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val after = b.speedKmh?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val dt = b.tMs - a.tMs
            if (dt !in 1..2_000) null else (after - before) * 1_000 / dt
        }
        fun p90(values: List<Double>) = if (values.isEmpty()) 0.0 else Stats.percentile(values, .9)
        val presses = window.indices.count { window[it].brake == true && (it == 0 || window[it - 1].brake != true) }
        val observedMs = window.zipWithNext().sumOf { (a, b) -> (b.tMs - a.tMs).coerceIn(0, 2_000) } +
            minOf(1_000L, end - window.last().tMs)
        val steering = Features.extract(RawTrip(window, emptyList(), emptyList()), null, emptySet(),
            ZoneId.of("Asia/Seoul")).steeringStd
        return StyleSample(moving.average(), p90(deltas.filter { it > 0 }), p90(deltas.filter { it < 0 }.map { -it }),
            presses * 60_000.0 / observedMs, steering)
    }
}

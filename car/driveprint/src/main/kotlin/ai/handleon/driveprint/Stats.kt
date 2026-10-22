package ai.handleon.driveprint

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor

@Serializable
data class Baseline(val median: Double, val mad: Double)

object Stats {
    fun median(xs: List<Double>): Double {
        require(xs.isNotEmpty() && xs.all(Double::isFinite)) { "Median needs finite observations" }
        val sorted = xs.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
    }

    fun baseline(xs: List<Double>): Baseline? {
        if (xs.size < 3) return null
        val center = median(xs)
        val mad = median(xs.map { abs(it - center) })
        return Baseline(center, maxOf(mad, 0.1 * abs(center), 0.01))
    }

    /** Linear interpolation between ranks 0 and n-1, with p expressed as a fraction. */
    fun percentile(xs: List<Double>, p: Double): Double {
        require(xs.isNotEmpty() && xs.all(Double::isFinite)) { "Percentile needs finite observations" }
        require(p in 0.0..1.0) { "Percentile must be in 0..1" }
        val sorted = xs.sorted()
        val rank = p * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val fraction = rank - lower
        return sorted[lower] * (1 - fraction) + sorted[upper] * fraction
    }

    fun z(x: Double, b: Baseline): Double {
        require(x.isFinite() && b.median.isFinite() && b.mad.isFinite() && b.mad > 0) {
            "Modified z needs finite values and a positive MAD; construct baselines with Stats.baseline"
        }
        return 0.6745 * (x - b.median) / b.mad
    }
}

package ai.handleon.driveprint

import kotlin.math.*

/** Normal-gamma / Student-t BOCPD, evaluated in log space to avoid posterior underflow. */
object Bocpd {
    fun lastChangeIndex(series: List<Double>, hazard: Double = 1.0 / 52): Int? {
        require(hazard > 0 && hazard < 1 && series.all(Double::isFinite))
        if (series.size < 3) return null
        // The API supplies no separate baseline: the first six weekly observations initialize mu0.
        val prior = Posterior(series.take(6).average(), 1.0, 1.0, 1.0)
        var states = listOf(prior)
        var probabilities = doubleArrayOf(0.0)
        for (x in series) {
            val joints = DoubleArray(states.size) { probabilities[it] + states[it].logPredictive(x) }
            val next = DoubleArray(states.size + 1)
            next[0] = logSum(joints) + ln(hazard)
            for (r in states.indices) next[r + 1] = joints[r] + ln1p(-hazard)
            val normalizer = logSum(next)
            probabilities = DoubleArray(next.size) { next[it] - normalizer }
            states = listOf(prior) + states.map { it.observe(x) }
        }
        val run = probabilities.indices.maxBy { probabilities[it] }
        return if (run < series.size - 1) series.size - 1 - run else null
    }

    private data class Posterior(val mu: Double, val kappa: Double, val alpha: Double, val beta: Double) {
        fun observe(x: Double) = Posterior((kappa * mu + x) / (kappa + 1), kappa + 1,
            alpha + 0.5, beta + kappa * (x - mu).pow(2) / (2 * (kappa + 1)))
        fun logPredictive(x: Double): Double {
            val scale = beta * (kappa + 1) / kappa
            return logGamma(alpha + 0.5) - logGamma(alpha) - 0.5 * ln(2 * PI * scale) -
                (alpha + 0.5) * ln1p((x - mu).pow(2) / (2 * scale))
        }
    }

    private fun logSum(xs: DoubleArray): Double {
        val max = xs.max()
        return max + ln(xs.sumOf { exp(it - max) })
    }

    // Lanczos g=7; all calls have positive arguments >= 1.
    private fun logGamma(value: Double): Double {
        val c = doubleArrayOf(676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7)
        val z = value - 1
        var x = 0.99999999999980993
        c.forEachIndexed { i, coefficient -> x += coefficient / (z + i + 1) }
        val t = z + 7.5
        return 0.5 * ln(2 * PI) + (z + 0.5) * ln(t) - t + ln(x)
    }
}

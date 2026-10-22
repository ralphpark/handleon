package ai.handleon.driveprint

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** z retains the raw signed modified z for later increase/decrease explanations. */
@Serializable
data class Contribution(val feature: FeatureId, val z: Double, val weighted: Double)

object Scoring {
    fun change(f: FeatureId, z: Double): Double = when (f.direction) {
        Direction.DOWN -> -z
        Direction.UP -> z
        Direction.BOTH -> abs(z)
    }

    /** scores must already contain change(feature, rawZ); direction is applied exactly once. */
    fun domainIndex(scores: Map<FeatureId, Double>, domain: Domain): Double? {
        val available = scores.filterKeys { it.domain == domain }
        if (available.isEmpty()) return null
        return available.entries.sumOf { (feature, score) -> feature.grade.weight * score } /
            available.keys.sumOf { it.grade.weight }
    }

    fun threshold(baselineIndices: List<Double>): Double =
        if (baselineIndices.isEmpty()) 1.5 else maxOf(1.5, Stats.percentile(baselineIndices, 0.95))

    /** scores are RAW signed modified z values; only ranking applies change and grade weight. */
    fun top(scores: Map<FeatureId, Double>, n: Int = 3): List<Contribution> {
        require(n >= 0) { "Number of contributions must be nonnegative" }
        return scores.map { (feature, score) -> Contribution(feature, score, feature.grade.weight * change(feature, score)) }
            .filter { it.weighted > 0 }
            .sortedWith(compareByDescending<Contribution> { it.weighted }.thenBy { it.feature.ordinal })
            .take(n)
    }
}

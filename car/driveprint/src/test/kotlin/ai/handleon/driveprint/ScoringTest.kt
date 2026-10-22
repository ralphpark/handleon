package ai.handleon.driveprint

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ScoringTest {
    @Test fun modifiedZMatchesIglewiczHoaglin() = assertEquals(0.6745 * (14 - 10) / 2.0, Stats.z(14.0, Baseline(10.0, 2.0)), 1e-12)
    @Test fun constantBaselineDoesNotExplode() { val b = Stats.baseline(listOf(0.0, 0.0, 0.0, 0.0))!!; assertEquals(0.01, b.mad, 1e-12); assertTrue(Stats.z(1.0, b).isFinite()) }
    @Test fun downFeatureDecreaseIsPositiveChange() = assertEquals(2.0, Scoring.change(FeatureId.M1, -2.0), 1e-12)
    @Test fun bothDirectionUsesMagnitude() = assertEquals(2.0, Scoring.change(FeatureId.O2, -2.0), 1e-12)
    @Test fun domainIndexWeightsByGrade() =   // W2(B 0.6) 변화 1.0, W3(C 0.3) 변화 4.0 → (0.6 + 1.2) / 0.9 = 2.0
        assertEquals(2.0, Scoring.domainIndex(mapOf(FeatureId.W2 to 1.0, FeatureId.W3 to 4.0), Domain.WAYFINDING)!!, 1e-12)
    @Test fun missingFeaturesAreSkipped() = assertNull(Scoring.domainIndex(mapOf(FeatureId.M1 to 1.0), Domain.OPERATION))
    @Test fun thresholdHasFloor() = assertEquals(1.5, Scoring.threshold(listOf(0.1, 0.2, 0.3)), 1e-12)

    @Test fun baselineNeedsThreeValuesAndUsesMedianAbsoluteDeviation() {
        assertNull(Stats.baseline(listOf(1.0, 2.0)))
        val b = Stats.baseline(listOf(6.0, 8.0, 10.0, 12.0, 1000.0))!!
        assertEquals(10.0, b.median, 1e-12)
        assertEquals(2.0, b.mad, 1e-12)
    }
    @Test fun madFloorScalesWithAbsoluteMedian() {
        assertEquals(10.0, Stats.baseline(listOf(100.0, 100.0, 100.0))!!.mad, 1e-12)
        assertEquals(10.0, Stats.baseline(listOf(-100.0, -100.0, -100.0))!!.mad, 1e-12)
    }
    @Test fun medianHandlesOddAndEvenWithoutMutatingInput() {
        val xs = listOf(8.0, 2.0, 4.0, 6.0)
        assertEquals(5.0, Stats.median(xs), 1e-12)
        assertEquals(4.0, Stats.median(listOf(8.0, 2.0, 4.0)), 1e-12)
        assertEquals(listOf(8.0, 2.0, 4.0, 6.0), xs)
    }
    @Test fun percentileInterpolatesAndHasExactEndpoints() {
        val xs = listOf(30.0, 0.0, 10.0, 20.0)
        assertEquals(28.5, Stats.percentile(xs, 0.95), 1e-12)
        assertEquals(0.0, Stats.percentile(xs, 0.0), 1e-12)
        assertEquals(30.0, Stats.percentile(xs, 1.0), 1e-12)
        assertEquals(7.0, Stats.percentile(listOf(7.0), 0.95), 1e-12)
        assertEquals(28.5, Scoring.threshold(xs), 1e-12)
    }
    @Test fun domainIndexDoesNotApplyDirectionTwiceOrCountMissingWeights() {
        assertEquals(-2.0, Scoring.domainIndex(mapOf(FeatureId.M1 to -2.0), Domain.MOBILITY)!!, 1e-12)
        assertEquals(4.0, Scoring.domainIndex(mapOf(FeatureId.W3 to 4.0), Domain.WAYFINDING)!!, 1e-12)
    }
    @Test fun topRanksPositiveWeightedChangesAndPreservesContribution() {
        val top = Scoring.top(mapOf(FeatureId.M1 to -2.0, FeatureId.O7 to 3.0,
            FeatureId.W3 to 5.0, FeatureId.O9 to -4.0, FeatureId.M2 to 0.0))
        assertEquals(listOf(FeatureId.M1, FeatureId.O7, FeatureId.W3), top.map { it.feature })
        assertEquals(3.0, top[1].z, 1e-12)
        assertEquals(1.8, top[1].weighted, 1e-12)
        assertEquals(1, Scoring.top(mapOf(FeatureId.W3 to 5.0, FeatureId.M1 to -2.0), n = 1).size)
        assertTrue(Scoring.top(mapOf(FeatureId.M1 to -2.0), n = 0).isEmpty())
    }
    @Test fun topKeepsSignedZWhileRankingDirectionalChange() {
        val top = Scoring.top(mapOf(FeatureId.M1 to -2.0, FeatureId.O2 to -3.0,
            FeatureId.O7 to -4.0, FeatureId.W3 to 5.0))
        assertEquals(listOf(FeatureId.O2, FeatureId.M1, FeatureId.W3), top.map { it.feature })
        assertEquals(-3.0, top[0].z, 1e-12)
        assertEquals(3.0, top[0].weighted, 1e-12)
        assertEquals(-2.0, top[1].z, 1e-12)
        assertEquals(2.0, top[1].weighted, 1e-12)
    }
    @Test fun emptyScoresDoNotInventAnIndexOrContributors() {
        assertNull(Scoring.domainIndex(emptyMap(), Domain.MOBILITY))
        assertTrue(Scoring.top(emptyMap()).isEmpty())
        assertEquals(1.5, Scoring.threshold(emptyList()), 1e-12)
    }
    @Test fun invalidStatisticalInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { Stats.median(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Stats.percentile(emptyList(), 0.95) }
        assertFailsWith<IllegalArgumentException> { Stats.percentile(listOf(1.0), 1.01) }
        assertFailsWith<IllegalArgumentException> { Stats.z(1.0, Baseline(0.0, 0.0)) }
    }
    @Test fun baselinesAndContributionsAreSerializable() {
        val b = Baseline(10.0, 2.0)
        val c = Contribution(FeatureId.M1, 2.0, 2.0)
        assertEquals(b, Json.decodeFromString<Baseline>(Json.encodeToString(b)))
        assertEquals(c, Json.decodeFromString<Contribution>(Json.encodeToString(c)))
    }
}

package ai.handleon.driveprint

import java.time.YearMonth
import java.util.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LongTrackTest {
    private val normal = mapOf(FeatureId.M1 to 50.0, FeatureId.M5 to 12.0, FeatureId.M7 to 30.0,
        FeatureId.W3 to 0.1, FeatureId.W4 to 0.1, FeatureId.O7 to 1.0)
    private fun months(n: Int, base: Map<FeatureId, Double> = normal, noise: Double = 0.02, seed: Long = 7): List<MonthInput> {
        val rnd = Random(seed)
        return List(n) { i -> MonthInput(YearMonth.of(2026, 1).plusMonths(i.toLong()),
            base.mapValues { (_, value) -> value * (1 + rnd.nextGaussian() * noise) }, 50) }
    }
    private fun shift(months: List<MonthInput>, from: Int, factor: Double, vararg features: FeatureId) =
        months.mapIndexed { i, m -> if (i + 1 < from) m else m.copy(values = m.values.mapValues { (f, v) ->
            if (f in features) v * factor else v
        }) }

    @Test fun fewMonthsIsBuilding() = assertEquals(Stage.BUILDING, LongTrack.evaluate(months(3)).stage)
    @Test fun noisyButStableIsStable() = assertEquals(Stage.STABLE, LongTrack.evaluate(months(12)).stage)
    @Test fun twoDomainsTwoMonthsIsTalk() {
        val m = shift(months(12), from = 10, factor = 0.5, FeatureId.M1, FeatureId.M5, FeatureId.M7)
            .let { shift(it, from = 10, factor = 3.0, FeatureId.W3, FeatureId.W4) }
        assertEquals(Stage.TALK, LongTrack.evaluate(m).stage)
        assertEquals(Stage.WATCH, LongTrack.evaluate(m.take(10)).stage)
    }
    @Test fun oneDomainTwoMonthsIsOnlyWatch() = assertEquals(Stage.WATCH, LongTrack.evaluate(shift(months(12), 10, 0.5, FeatureId.M1, FeatureId.M5)).stage)
    @Test fun resetRestartsBaseline() = assertEquals(Stage.BUILDING, LongTrack.evaluate(months(12), resetMonths = setOf(YearMonth.of(2026, 9))).stage)
    @Test fun topExplainsLargestWeightedChanges() {
        val m = shift(months(12), 10, 0.2, FeatureId.M1, FeatureId.M5, FeatureId.M7)
            .let { shift(it, 10, 3.0, FeatureId.W3, FeatureId.W4) }
        val r = LongTrack.evaluate(m)
        assertEquals(Stage.TALK, r.stage)
        assertEquals(Domain.MOBILITY, r.top.first().feature.domain)
        assertTrue(r.top.first().z < 0)
        assertTrue(r.top.zipWithNext().all { (a, b) -> a.weighted >= b.weighted })
    }
    @Test fun baselineRequiresSixCalendarMonthsAndFiftyTrips() {
        assertEquals(Stage.BUILDING, LongTrack.evaluate(months(5)).stage)
        assertEquals(Stage.BUILDING, LongTrack.evaluate(months(6).map { it.copy(trips = 8) }).stage)
        assertEquals(Stage.STABLE, LongTrack.evaluate(months(7).map { it.copy(trips = 8) }).stage)
    }
    @Test fun sparseMonthsMustNotCountAsConsecutive() {
        val m = shift(months(12), 10, 0.2, FeatureId.M1, FeatureId.M5, FeatureId.M7)
            .let { shift(it, 10, 3.0, FeatureId.W3, FeatureId.W4) }
            .filter { it.month != YearMonth.of(2026, 11) }
        assertEquals(Stage.WATCH, LongTrack.evaluate(m).stage)
    }
    @Test fun futureResetDoesNotEraseCurrentEvidence() {
        assertEquals(Stage.STABLE, LongTrack.evaluate(months(12), setOf(YearMonth.of(2027, 1))).stage)
    }
    @Test fun longReportRoundTripsMonthStrings() {
        val r = LongTrack.evaluate(months(12))
        val text = Json.encodeToString(r)
        assertTrue("2026-07" in text)
        assertEquals(r, Json.decodeFromString<LongReport>(text))
    }
}

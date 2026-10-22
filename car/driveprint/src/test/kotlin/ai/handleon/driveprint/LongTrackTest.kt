package ai.handleon.driveprint

import java.time.YearMonth
import java.time.LocalDate
import java.util.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LongTrackTest {
    private fun staggeredWayfinding(lateStart: Int, spikeInOverlap: Boolean = false): List<MonthInput> =
        List(12) { i -> MonthInput(YearMonth.of(2026, 1).plusMonths(i.toLong()), buildMap {
            put(FeatureId.W2, if (i == 0 || spikeInOverlap && i == 5) 0.05 else 0.5)
            if (i >= lateStart) put(FeatureId.W4, if (i == 7 || spikeInOverlap && i == 5) 2.0 else 0.1)
        }, 50) }
    @Test fun domainThresholdUsesOnlySharedBaselineMonths() {
        val report = LongTrack.evaluate(staggeredWayfinding(lateStart = 3))
        assertEquals(1.5, report.thresholds.getValue(Domain.WAYFINDING))
    }
    @Test fun fewerThanThreeSharedMonthsUseThresholdFloor() {
        val report = LongTrack.evaluate(staggeredWayfinding(lateStart = 4, spikeInOverlap = true))
        assertEquals(1.5, report.thresholds.getValue(Domain.WAYFINDING))
    }
    @Test fun threeSharedMonthsCalibrateTheirOwnIndices() {
        val report = LongTrack.evaluate(staggeredWayfinding(lateStart = 3, spikeInOverlap = true))
        assertEquals(4.5, report.thresholds.getValue(Domain.WAYFINDING), 1e-9)
    }
    @Test fun twoGuidedTripsPerMonthCloseW3WindowAfterSixMonths() {
        val first = YearMonth.of(2026, 1)
        val trips = (0L until 6).flatMap { offset -> List(20) { i ->
            val start = first.plusMonths(offset).atDay(i + 1).atTime(12, 0)
                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
            TripFeatures(start, start + 600_000, Source.CAR, 10.0, false, false, false,
                1L, 10.0, true, i < 2, if (i < 2) 10.0 else 0.0, if (i < 2) 1 else 0,
                2, 2, 0, 0, null, 0, null, null, 0, 0)
        } }
        val report = Driveprint().longReport(trips, emptySet(), emptySet(), first.plusMonths(6).atDay(1))
        assertEquals(BaselineWindow(first, first.plusMonths(6)), report.baselineWindows[FeatureId.W3])
    }
    @Test fun monthsWithoutFeatureStillCountTowardFiftyTrips() {
        val input = months(6).mapIndexed { i, m -> m.copy(trips = 9,
            values = if (i in listOf(1, 3)) m.values - FeatureId.W3 else m.values) }
        assertEquals(YearMonth.of(2026, 7), LongTrack.evaluate(input).baselineWindows[FeatureId.W3]?.toExclusive)
    }
    @Test fun baselineExtensionStopsAtNineMonths() {
        val sparse = months(12).mapIndexed { i, m ->
            if (i in listOf(0, 4, 8, 9)) m else m.copy(values = m.values - FeatureId.W3)
        }
        assertFalse(FeatureId.W3 in LongTrack.evaluate(sparse).baselines)
        val ninthMonth = LongTrack.evaluate(months(10).map { it.copy(trips = 6) })
        assertEquals(YearMonth.of(2026, 10), ninthMonth.baselineWindows.getValue(FeatureId.W3).toExclusive)
    }
    @Test fun homeChangeThresholdMatchesScoredFeatures() {
        val months = List(7) { i ->
            MonthInput(YearMonth.of(2026, 1).plusMonths(i.toLong()), mapOf(
                FeatureId.M1 to if (i == 6) 76.0 else 100.0,
                FeatureId.M7 to if (i == 5) 0.0 else 100.0), 50)
        }
        val report = LongTrack.evaluate(months, setOf(Reset(LocalDate.of(2026, 7, 1), ResetKind.HOME_CHANGED)))
        assertEquals(1.5, report.thresholds.getValue(Domain.MOBILITY))
        assertTrue(report.months.last().domains.getValue(Domain.MOBILITY) > report.thresholds.getValue(Domain.MOBILITY))
        assertTrue(Domain.MOBILITY in report.months.last().over)
    }
    @Test fun homeChangeKeepsOtherBaselines() {
        val input = shift(months(18), 10, .2, FeatureId.M7)
        val reset = setOf(Reset(LocalDate.of(2026, 10, 1), ResetKind.HOME_CHANGED))
        val before = LongTrack.evaluate(input.take(9))
        val held = LongTrack.evaluate(input.take(15), resets = reset)
        assertEquals(before.baselines, held.baselines)
        assertFalse(held.top.any { it.feature == FeatureId.M7 })
        assertTrue(LongTrack.evaluate(input.take(16), resets = reset).top.any { it.feature == FeatureId.M7 })
    }
    @Test fun carChangeResetsOnlyOperation() {
        val input = shift(months(18), 10, 3.0, FeatureId.O7)
        val reset = setOf(Reset(LocalDate.of(2026, 10, 1), ResetKind.CAR_CHANGED))
        val before = LongTrack.evaluate(input.take(9))
        val buildingOperation = LongTrack.evaluate(input.take(12), resets = reset)
        assertEquals(before.baselines.filterKeys { it.domain != Domain.OPERATION }, buildingOperation.baselines)
        assertTrue(buildingOperation.stage != Stage.BUILDING)
        assertFalse(Domain.OPERATION in buildingOperation.months.last().domains)
        assertTrue(LongTrack.evaluate(input, resets = reset).baselines.getValue(FeatureId.O7).median > 2.5)
    }
    @Test fun lateFeatureBaselineExtendsUntilFourObservedMonths() {
        val input = months(15).mapIndexed { i, m ->
            if (i < 6 || i in listOf(7, 9, 11)) m.copy(values = m.values - FeatureId.W3) else m
        }
        assertFalse(FeatureId.W3 in LongTrack.evaluate(input.take(12)).baselines)
        assertTrue(FeatureId.W3 in LongTrack.evaluate(input.take(13)).baselines)
        assertTrue(Domain.WAYFINDING in LongTrack.evaluate(input).thresholds)
    }
    @Test fun noScoredMonthIsBuilding() {
        val r = LongTrack.evaluate(months(6))
        assertEquals(Stage.BUILDING, r.stage)
        assertTrue(r.baselines.isNotEmpty())
        assertTrue(r.months.isEmpty())
    }
    @Test fun inactiveMonthKeepsChain() {
        val shifted = shift(months(12), 10, 0.2, FeatureId.M1, FeatureId.M5, FeatureId.M7)
            .let { shift(it, 10, 3.0, FeatureId.W3, FeatureId.W4) }
        val missing = shifted.map { if (it.month == YearMonth.of(2026, 11)) it.copy(values = emptyMap(), trips = 0) else it }
        assertEquals(Stage.TALK, LongTrack.evaluate(missing).stage)
    }
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
    @Test fun resetRestartsBaseline() = assertEquals(Stage.BUILDING, LongTrack.evaluate(months(12), resets = setOf(Reset(LocalDate.of(2026, 9, 1), ResetKind.ALL))).stage)
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
        assertEquals(Stage.BUILDING, LongTrack.evaluate(months(7).map { it.copy(trips = 8) }).stage)
        assertEquals(Stage.STABLE, LongTrack.evaluate(months(8).map { it.copy(trips = 8) }).stage)
    }
    @Test fun inactiveMonthsDoNotBreakEvaluatedMonthChain() {
        val m = shift(months(12), 10, 0.2, FeatureId.M1, FeatureId.M5, FeatureId.M7)
            .let { shift(it, 10, 3.0, FeatureId.W3, FeatureId.W4) }
            .filter { it.month != YearMonth.of(2026, 11) }
        assertEquals(Stage.TALK, LongTrack.evaluate(m).stage)
    }
    @Test fun futureResetDoesNotEraseCurrentEvidence() {
        assertEquals(Stage.STABLE, LongTrack.evaluate(months(12), setOf(Reset(LocalDate.of(2027, 1, 1), ResetKind.ALL))).stage)
    }
    @Test fun longReportRoundTripsMonthStrings() {
        val r = LongTrack.evaluate(months(12))
        val text = Json.encodeToString(r)
        assertTrue("2026-07" in text)
        assertEquals(r, Json.decodeFromString<LongReport>(text))
    }
}

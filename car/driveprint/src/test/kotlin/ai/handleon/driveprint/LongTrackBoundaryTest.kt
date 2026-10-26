package ai.handleon.driveprint

import java.time.LocalDate
import java.time.YearMonth
import java.util.Random
import kotlin.test.*

class LongTrackBoundaryTest {
    private fun result(i: Long, vararg over: Domain) =
        MonthResult(YearMonth.of(2026, 1).plusMonths(i), over.associateWith { 2.0 }, over.toSet())
    private val normal = mapOf(FeatureId.M1 to 50.0, FeatureId.M5 to 12.0, FeatureId.M7 to 30.0,
        FeatureId.W3 to 0.1, FeatureId.W4 to 0.1, FeatureId.O7 to 1.0)
    private fun months(n: Int, seed: Long = 7): List<MonthInput> {
        val rnd = Random(seed)
        return List(n) { i -> MonthInput(YearMonth.of(2026, 1).plusMonths(i.toLong()),
            normal.mapValues { (_, value) -> value * (1 + rnd.nextGaussian() * 0.02) }, 50) }
    }
    private fun shift(months: List<MonthInput>, from: Int, factor: Double, vararg features: FeatureId) =
        months.mapIndexed { i, m -> if (i + 1 < from) m else m.copy(values = m.values.mapValues { (f, v) ->
            if (f in features) v * factor else v
        }) }
    private fun reset(day: String, kind: ResetKind) = Reset(LocalDate.parse(day), kind)

    @Test fun ruleDTalksWithThreeDomainsAndOneRepeated() = assertEquals(Stage.TALK,
        LongTrack.stageOf(result(1, Domain.MOBILITY, Domain.WAYFINDING, Domain.OPERATION), result(0, Domain.OPERATION)))

    @Test fun ruleDPreviousMonthWithNothingOverIsWatch() =
        assertEquals(Stage.WATCH, LongTrack.stageOf(result(1, Domain.MOBILITY, Domain.WAYFINDING), result(0)))

    @Test fun ruleDOneRepeatedDomainAloneIsWatch() =
        assertEquals(Stage.WATCH, LongTrack.stageOf(result(1, Domain.MOBILITY), result(0, Domain.MOBILITY)))

    @Test fun stageUsesOverSetNotRawDomainIndices() {
        val high = MonthResult(YearMonth.of(2026, 2), mapOf(Domain.MOBILITY to 9.0, Domain.WAYFINDING to 9.0), emptySet())
        assertEquals(Stage.STABLE, LongTrack.stageOf(high, result(0, Domain.MOBILITY, Domain.WAYFINDING)))
    }

    @Test fun emptyInputIsBuildingWithNothingElse() {
        val r = LongTrack.evaluate(emptyList())
        assertEquals(Stage.BUILDING, r.stage)
        assertTrue(r.months.isEmpty()); assertTrue(r.baselines.isEmpty()); assertTrue(r.thresholds.isEmpty())
        assertTrue(r.baselineWindows.isEmpty())
    }

    @Test fun duplicateMonthIsRejected() {
        val input = months(8)
        assertFailsWith<IllegalArgumentException> { LongTrack.evaluate(input + input.last()) }
    }

    @Test fun inputOrderDoesNotChangeReport() {
        val input = shift(months(12), 10, 0.2, FeatureId.M1, FeatureId.M5, FeatureId.M7)
        assertEquals(LongTrack.evaluate(input), LongTrack.evaluate(input.shuffled(Random(3))))
    }

    @Test fun resetsAfterLastMonthAreIgnoredForEveryKind() {
        val input = months(12)
        val plain = LongTrack.evaluate(input)
        for (kind in ResetKind.entries) assertEquals(plain, LongTrack.evaluate(input, setOf(reset("2027-01-01", kind))), "$kind")
    }

    @Test fun fullResetInLastMonthLeavesOnlyThatMonthAndBuilds() {
        val r = LongTrack.evaluate(months(12), setOf(reset("2026-12-31", ResetKind.ALL)))
        assertEquals(Stage.BUILDING, r.stage)
        assertTrue(r.baselines.isEmpty())
    }

    @Test fun latestFullResetWins() {
        val input = months(18)
        assertEquals(LongTrack.evaluate(input, setOf(reset("2026-09-01", ResetKind.ALL))),
            LongTrack.evaluate(input, setOf(reset("2026-02-01", ResetKind.ALL), reset("2026-09-01", ResetKind.ALL))))
    }

    @Test fun homeChangeMidMonthExcludesTheWholeMonth() {
        val input = shift(months(18), 10, .2, FeatureId.M7)
        for (n in listOf(10, 15, 16)) assertEquals(
            LongTrack.evaluate(input.take(n), setOf(reset("2026-10-01", ResetKind.HOME_CHANGED))),
            LongTrack.evaluate(input.take(n), setOf(reset("2026-10-31", ResetKind.HOME_CHANGED))), "months=$n")
    }

    @Test fun homeChangeInLastMonthHidesHomeDistanceAtOnce() {
        val input = shift(months(12), 10, .2, FeatureId.M7)
        assertTrue(LongTrack.evaluate(input).top.any { it.feature == FeatureId.M7 })
        val held = LongTrack.evaluate(input, setOf(reset("2026-12-15", ResetKind.HOME_CHANGED)))
        assertFalse(held.top.any { it.feature == FeatureId.M7 })
        assertEquals(LongTrack.evaluate(input).baselines, held.baselines)
    }

    @Test fun carChangeMidMonthCountsFromThatMonth() {
        val input = shift(months(18), 10, 3.0, FeatureId.O7)
        for (n in listOf(12, 18)) assertEquals(
            LongTrack.evaluate(input.take(n), setOf(reset("2026-10-01", ResetKind.CAR_CHANGED))),
            LongTrack.evaluate(input.take(n), setOf(reset("2026-10-31", ResetKind.CAR_CHANGED))), "months=$n")
    }

    @Test fun latestCarChangeWins() {
        val input = shift(months(18), 10, 3.0, FeatureId.O7)
        assertEquals(LongTrack.evaluate(input, setOf(reset("2026-10-01", ResetKind.CAR_CHANGED))),
            LongTrack.evaluate(input, setOf(reset("2026-04-01", ResetKind.CAR_CHANGED), reset("2026-10-01", ResetKind.CAR_CHANGED))))
    }

    @Test fun carChangeInLastMonthDropsOperationAtOnce() {
        val input = months(12)
        val r = LongTrack.evaluate(input, setOf(reset("2026-12-01", ResetKind.CAR_CHANGED)))
        assertFalse(FeatureId.O7 in r.baselines)
        assertFalse(Domain.OPERATION in r.months.last().domains)
        assertEquals(LongTrack.evaluate(input).baselines.filterKeys { it.domain != Domain.OPERATION }, r.baselines)
    }
}

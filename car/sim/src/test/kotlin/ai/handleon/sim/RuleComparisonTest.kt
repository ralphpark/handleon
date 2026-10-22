package ai.handleon.sim

import ai.handleon.driveprint.*
import kotlin.test.*

class RuleComparisonTest {
    private val start = CohortRun.START
    private fun month(offset: Long, m: Double, w: Double, o: Double = 0.0): MonthResult {
        val domains = mapOf(Domain.MOBILITY to m, Domain.WAYFINDING to w, Domain.OPERATION to o)
        return MonthResult(start.plusMonths(offset), domains, domains.filterValues { it > 1.5 }.keys)
    }
    @Test fun dNeedsTwoCurrentDomainsAndOneRepeatedDomain() {
        val monthly = listOf(month(6, 2.0, 0.0), month(7, 2.0, 2.0), month(8, 2.0, 0.0, 2.0))
        assertEquals(listOf(Stage.WATCH, Stage.WATCH, Stage.WATCH), RuleComparison.stages(monthly, start, 9, Rule.A).takeLast(3))
        assertEquals(listOf(Stage.WATCH, Stage.TALK, Stage.TALK), RuleComparison.stages(monthly, start, 9, Rule.D).takeLast(3))
    }
    @Test fun kUsesFixedOnePointTwoWithoutChangingStoredOverSets() {
        val monthly = listOf(month(6, 1.3, 1.3), month(7, 1.3, 1.3))
        assertEquals(Stage.STABLE, RuleComparison.stages(monthly, start, 8, Rule.A).last())
        assertEquals(Stage.TALK, RuleComparison.stages(monthly, start, 8, Rule.K).last())
        assertTrue(monthly.all { it.over.isEmpty() })
    }
    @Test fun inactiveMonthsDoNotBecomeExtraPersistenceEvidence() {
        val monthly = listOf(month(6, 2.0, 2.0), month(8, 2.0, 2.0))
        assertEquals(listOf(Stage.WATCH, Stage.WATCH, Stage.TALK), RuleComparison.stages(monthly, start, 9, Rule.A).takeLast(3))
    }
    @Test fun watchRatesUseScoredMonthsAndPeople() {
        val monthly = listOf(month(6, 0.0, 0.0), month(7, 0.0, 0.0), month(8, 0.0, 0.0))
        val normal = CohortRun.outcome(0, null, List(6) { Stage.BUILDING } + listOf(Stage.WATCH, Stage.WATCH, Stage.STABLE), monthly)
        val stable = CohortRun.outcome(1, null, List(6) { Stage.BUILDING } + List(3) { Stage.STABLE }, monthly)
        val changed = CohortRun.outcome(2, start.plusMonths(6), List(6) { Stage.BUILDING } + List(3) { Stage.TALK }, monthly)
        val metrics = CohortRun.metrics(listOf(normal, stable, changed))
        assertEquals(2.0 / 6, metrics.normalWatchMonthRatio, 1e-9)
        assertEquals(.5, metrics.normalWatchPersonRatio, 1e-9)
    }
}

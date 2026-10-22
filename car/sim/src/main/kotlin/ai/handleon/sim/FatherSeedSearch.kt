package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.YearMonth

data class FatherSeedCandidate(
    val seed: Long, val rule: Rule, val firstTalk: YearMonth, val lastStage: Stage,
    val onsetWeeksAgo: Int?, val monthly: List<MonthResult>,
)

/** Fixed father parameters; only the random seed varies, and rules reclassify the same monthly indices. */
object FatherSeedSearch {
    fun search(seeds: LongRange = 1L..200L): Map<Rule, List<FatherSeedCandidate>> {
        require(!seeds.isEmpty() && seeds.first >= 1)
        val candidates = seeds.flatMap { seed ->
            val report = Driveprint().longReport(Personas.father(seed), emptySet(), emptySet(),
                YearMonth.of(2026, 11).atDay(1))
            listOf(Rule.A, Rule.D).mapNotNull { candidate(seed, it, report) }
        }
        return listOf(Rule.A, Rule.D).associateWith { rule -> candidates.filter { it.rule == rule } }
    }

    internal fun candidate(seed: Long, rule: Rule, report: LongReport): FatherSeedCandidate? {
        val stages = RuleComparison.stages(report.months, CohortRun.START, 24, rule)
        val firstIndex = stages.indexOfFirst { it == Stage.TALK }.takeIf { it >= 0 } ?: return null
        val firstTalk = CohortRun.START.plusMonths(firstIndex.toLong())
        if (firstTalk !in YearMonth.of(2026, 6)..YearMonth.of(2026, 9) || stages.last() != Stage.TALK) return null
        return FatherSeedCandidate(seed, rule, firstTalk, stages.last(), report.onsetWeeksAgo, report.months)
    }
}

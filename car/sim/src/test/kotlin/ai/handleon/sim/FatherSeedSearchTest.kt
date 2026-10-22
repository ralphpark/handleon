package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.YearMonth
import kotlin.test.*

class FatherSeedSearchTest {
    private fun report(firstOver: Int = 18, firstOnlyMobility: Boolean = true, finalTalk: Boolean = true): LongReport {
        val months = (6..23).map { i ->
            val m = if (i >= firstOver && (i < 23 || finalTalk)) 2.0 else 0.0
            val w = if (i == firstOver && firstOnlyMobility) 0.0 else m
            val indices = mapOf(Domain.MOBILITY to m, Domain.WAYFINDING to w)
            MonthResult(CohortRun.START.plusMonths(i.toLong()), indices, indices.filterValues { it > 1.5 }.keys)
        }
        return LongReport(Stage.TALK, months, emptyMap(), emptyMap(), emptyList(), 50)
    }
    @Test fun candidateReclassifiesTheSameMonthsUnderAAndD() {
        val report = report()
        val a = FatherSeedSearch.candidate(7, Rule.A, report)!!
        val d = FatherSeedSearch.candidate(7, Rule.D, report)!!
        assertEquals(YearMonth.of(2026, 7), a.firstTalk)
        assertEquals(YearMonth.of(2026, 6), d.firstTalk)
        assertEquals(Stage.TALK, a.lastStage)
        assertEquals(report.months, a.monthly)
        assertEquals(report.months, d.monthly)
    }
    @Test fun candidateRequiresBothSummerFirstTalkAndFinalTalk() {
        assertNull(FatherSeedSearch.candidate(7, Rule.A, report(firstOver = 16)))
        assertNull(FatherSeedSearch.candidate(7, Rule.A, report(firstOver = 22, firstOnlyMobility = false)))
        assertNull(FatherSeedSearch.candidate(7, Rule.A, report(finalTalk = false)))
    }
}

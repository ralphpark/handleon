package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test
import kotlin.test.*

class UiModelsTest {
    @Test fun letterCardAppearsOnlyWithLetterOnCurrentTab() {
        val letter = LetterUi(YearMonth.of(2026, 10), "첫 줄\n핸들온 드림")
        assertNull(MaeumUi.from(null, null).visibleLetter)
        val ui = MaeumUi.from(null, null, letter = letter)
        assertEquals("10월 운전 편지", ui.visibleLetter!!.title)
        assertEquals("첫 줄", ui.visibleLetter!!.preview)
        assertNull(ui.copy(pastSelected = true).visibleLetter)
    }
    private fun report(stage: Stage) = LongReport(stage, emptyList(), emptyMap(), emptyMap(), emptyList(), null)

    @Test fun talkShowsCenterAndOtherStagesDoNot() {
        Stage.entries.forEach { assertEquals(it == Stage.TALK, MaeumUi.from(report(it), null).showCenter) }
    }
    @Test fun buildingUsesEngineCopyAndRealCollectionCounts() {
        val ui = MaeumUi.from(report(Stage.BUILDING), null, CollectionProgress(3, 42))
        assertEquals("운전 지문을 모으는 중이에요", ui.title)
        assertEquals("3개월 · 42트립을 모았어요", ui.collectionText)
    }
    @Test fun noHomeShowsHintButRegisteredHomeDoesNot() {
        assertEquals(HOME_HINT, MaeumUi.from(report(Stage.STABLE), null).homeHint)
        assertNull(MaeumUi.from(report(Stage.STABLE), LatLon(37.4, 127.1)).homeHint)
    }
    @Test fun conditionKeepsLatestFourteenDrivingDaysAndIndex() {
        val days = (1..20).map { DayResult(LocalDate.of(2026, 10, it), it.toDouble(), 99.0, 10.0, false) }
        val ui = ConditionUi.from(ConditionReport(ConditionStatus.OK, days, emptyList()))
        assertEquals(days.takeLast(14), ui.days)
        assertEquals(20.0, ui.days.last().index)
    }
    @Test fun okConditionExplainsOnlyADayOverTheLine() {
        val top = listOf(Contribution(FeatureId.O2, 1.2, 1.2), Contribution(FeatureId.O7, 0.8, 0.8))
        fun ui(status: ConditionStatus, over: Boolean) = ConditionUi.from(ConditionReport(status,
            listOf(DayResult(LocalDate.of(2026, 10, 28), 1.0, 1.0, 2.0, alert = status == ConditionStatus.CAUTION, over = over)), top))
        ui(ConditionStatus.OK, over = false).let {
            assertEquals("오늘 운전 컨디션 괜찮아요", it.title)
            assertTrue(it.reasons.isEmpty())
            assertEquals("평소 범위 안에서 운전하셨어요.", it.note)
        }
        ui(ConditionStatus.OK, over = true).let {
            assertEquals(2, it.reasons.size)
            assertEquals("오늘 하루만 평소와 조금 달랐어요. 이틀 이어지면 알려 드려요.", it.note)
        }
        ui(ConditionStatus.CAUTION, over = true).let { assertEquals(2, it.reasons.size); assertNull(it.note) }
        assertEquals("나만의 기준이 쌓이면 평소와 견주어 알려 드릴게요.", ConditionUi.from(null).note)
    }
    @Test fun age59ChoosesConditionAnd60ChoosesMaeum() {
        val today = LocalDate.of(2026, 10, 23)
        assertEquals(Mode.CONDITION, modeForBirthYear(1967, today))
        assertEquals(Mode.MAEUM, modeForBirthYear(1966, today))
    }
    @Test fun invalidYearDoesNotInventRecommendation() {
        assertNull(modeForBirthYear(2027, LocalDate.of(2026, 1, 1)))
        assertNull(modeForBirthYear(0, LocalDate.of(2026, 1, 1)))
    }
    @Test fun consentGateRequiresAllThreeAndValidYear() {
        val base = FirstRunUi(birthYear = "1956", mode = Mode.MAEUM)
        assertFalse(base.canStart(LocalDate.of(2026, 1, 1)))
        assertFalse(base.copy(consents = listOf(true, true, false)).canStart(LocalDate.of(2026, 1, 1)))
        assertTrue(base.copy(consents = listOf(true, true, true)).canStart(LocalDate.of(2026, 1, 1)))
    }
    @Test fun ringsKeepMissingDistinctFromZeroAndUseStrictThreshold() {
        val rings = ringFractions(mapOf(Domain.MOBILITY to 1.5, Domain.WAYFINDING to 3.0),
            mapOf(Domain.MOBILITY to 1.5, Domain.WAYFINDING to 1.5))
        assertFalse(rings[0].over)
        assertTrue(rings[1].over)
        assertNull(rings[2].fraction)
        assertEquals(0.6f, rings[0].fraction)
        assertEquals(1f, rings[1].fraction)
    }
    @Test fun topThreeAndOnsetComeFromCopy() {
        val top = FeatureId.entries.take(4).map { Contribution(it, -2.0, 2.0) }
        val ui = MaeumUi.from(report(Stage.TALK).copy(top = top, onsetWeeksAgo = 6), null)
        assertEquals(top.take(3).map(Copy::line), ui.reasons.map { it.line })
        assertEquals(top.take(3).map { Copy.evidence(it.feature) }, ui.reasons.map { it.evidence })
        assertEquals(Copy.onset(6), ui.onset)
    }
    @Test fun latestEvaluatedMonthDrivesRings() {
        val month = MonthResult(YearMonth.of(2026, 9), mapOf(Domain.MOBILITY to 2.0), setOf(Domain.MOBILITY))
        val ui = MaeumUi.from(report(Stage.WATCH).copy(months = listOf(month), thresholds = mapOf(Domain.MOBILITY to 1.5)), null)
        assertTrue(ui.rings.first().over)
        assertEquals("2026년 9월 · 마지막 평가 기록", ui.period)
    }
    @Test fun chartRangeKeepsNegativeIndicesBelowZero() {
        val range = chartRange(listOf(-2.0, 0.0, 1.5, 3.0))
        assertTrue(range.fraction(-2.0) < range.fraction(0.0))
        assertTrue(range.fraction(0.0) < range.fraction(1.5))
        assertTrue(range.fraction(3.0) <= 1f)
    }
    @Test fun emptyChartRangeIsFinite() {
        val range = chartRange(listOf(Double.NaN, Double.POSITIVE_INFINITY))
        assertTrue(range.fraction(0.0).isFinite())
    }
}

package ai.handleon.driveprint

import java.time.LocalDate
import java.util.Random
import kotlin.test.*

class ShortTrackTest {
    private val start = LocalDate.of(2026, 1, 1)
    private fun days(n: Int, startAfter: Int = 0): List<DayInput> {
        val random = Random(7)
        return List(n) { DayInput(start.plusDays((startAfter + it).toLong()),
            mapOf(FeatureId.O7 to 1.5 + random.nextGaussian() * 0.02), 2, false) }
    }
    private fun badDays(n: Int, steeringStd: Double, labeled: Boolean = false) =
        List(n) { DayInput(start.plusDays(30L + it), mapOf(FeatureId.O7 to steeringStd), 2, labeled) }
    private fun nightOnlyDay() = listOf(DayInput(start.plusDays(30), emptyMap(), 2, false))

    @Test fun fewDaysIsBuilding() = assertEquals(ConditionStatus.BUILDING, ShortTrack.evaluate(days(5)).status)
    @Test fun twoBadDaysInARowIsCaution() = assertEquals(ConditionStatus.CAUTION, ShortTrack.evaluate(days(30) + badDays(2, steeringStd = 3.0)).status)
    @Test fun singleBadDayIsStillOk() = assertEquals(ConditionStatus.OK, ShortTrack.evaluate(days(30) + badDays(1, steeringStd = 3.0) + days(1, startAfter = 31)).status)
    @Test fun labeledBadDaysDoNotAlert() = assertEquals(ConditionStatus.OK, ShortTrack.evaluate(days(30) + badDays(2, steeringStd = 3.0, labeled = true)).status)
    @Test fun nightOnlyDayIsHold() = assertEquals(ConditionStatus.HOLD, ShortTrack.evaluate(days(30) + nightOnlyDay()).status)
    @Test fun fifteenDayGapRestartsBaseline() = assertEquals(ConditionStatus.BUILDING, ShortTrack.evaluate(days(30) + days(3, startAfter = 45)).status)
    @Test fun explicitResetAndRollingWindowRequireFreshSevenDays() {
        val r = ShortTrack.evaluate(days(30), setOf(start.plusDays(25)))
        assertEquals(ConditionStatus.BUILDING, r.status)
        assertNull(r.days.last().index)
    }
    @Test fun holdDayBreaksConsecutiveDrivingDayEvidence() {
        val input = days(30) + badDays(1, 3.0) +
            DayInput(start.plusDays(31), emptyMap(), 2, false) +
            DayInput(start.plusDays(32), mapOf(FeatureId.O7 to 3.0), 2, false)
        assertFalse(ShortTrack.evaluate(input).days.last().alert)
    }
    @Test fun onlyConditionFeaturesParticipate() {
        val input = days(30).map { it.copy(values = it.values + (FeatureId.O1 to 0.1)) } +
            days(1, 30).map { it.copy(values = it.values + (FeatureId.O1 to 100.0)) }
        assertEquals(ConditionStatus.OK, ShortTrack.evaluate(input).status)
        assertFalse(ShortTrack.evaluate(input).top.any { it.feature == FeatureId.O1 })
    }
    @Test fun firstSevenDrivingDaysBuildThenEighthHasIndex() {
        val r = ShortTrack.evaluate(days(8))
        assertTrue(r.days.take(7).all { it.index == null })
        assertNotNull(r.days.last().index)
        assertEquals(1.5, r.days.last().threshold!!, 1e-12)
    }
}

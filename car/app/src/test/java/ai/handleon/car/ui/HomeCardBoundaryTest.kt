package ai.handleon.car.ui

import ai.handleon.car.collect.DriveSessionView
import kotlin.test.*

class HomeCardBoundaryTest {
    private val line = TodayLineUi("가까운 곳을 10분 걸어 볼까요?", "일주일에 세 번 이상 걷기는 3·3·3 수칙의 첫 번째예요.")
    private val note = CarNote(1, "큰딸", "천천히 오세요", "2026-10-25T00:00:00Z")
    private val twoHours = 2 * 3_600_000L

    @Test fun restCardWinsInEveryTimerState() {
        for (rest in listOf(RestUi(), RestUi(remainingMs = 1), RestUi(remainingMs = 0, finished = true))) {
            assertEquals(HomeCard.Rest(rest), topCard(rest, note, line))
        }
    }

    @Test fun eachCardCarriesItsOwnValue() {
        assertEquals(HomeCard.Rest(RestUi()), topCard(RestUi(), null, null))
        assertEquals(HomeCard.Note(note), topCard(null, note, line))
        assertEquals(HomeCard.Note(note), topCard(null, note, null))
        assertEquals(HomeCard.Line(line), topCard(null, null, line))
    }

    @Test fun restEligibleAtExactlyTheThresholdAndWithinHalfAnHourOfParking() {
        val session = DriveSessionView(0, twoHours)
        assertTrue(restEligible(session, twoHours, twoHours))
        assertFalse(restEligible(DriveSessionView(1, twoHours), twoHours, twoHours))
        assertTrue(restEligible(session, twoHours + 30 * 60_000L - 1, twoHours))
        assertFalse(restEligible(session, twoHours - 1, twoHours)) // clock behind the last moving tick
        assertFalse(restEligible(null, twoHours, 0))
    }

    @Test fun timerTitleRoundsUpToWholeSecondsAndNeverGoesNegative() {
        assertEquals("00:00", RestUi(0).title)
        assertEquals("00:00", RestUi(-5_000).title)
        assertEquals("01:00", RestUi(59_001).title)
        assertEquals("01:00", RestUi(60_000).title)
        assertEquals("01:01", RestUi(60_001).title)
        assertEquals("잠깐 쉬어 가요", RestUi().title)
        assertEquals(REST_BODY, RestUi(1).body)
        assertEquals(REST_FINISHED, RestUi(finished = true).body)
    }

    @Test fun continuousDriveTextAtMinuteAndHourBoundaries() {
        assertEquals("연속 운전 0분", continuousDriveText(-1))
        assertEquals("연속 운전 0분", continuousDriveText(59_999))
        assertEquals("연속 운전 1분", continuousDriveText(60_000))
        assertEquals("연속 운전 59분", continuousDriveText(60 * 60_000L - 1))
        assertEquals("연속 운전 1시간 0분", continuousDriveText(60 * 60_000L))
        assertEquals("연속 운전 25시간 1분", continuousDriveText(25 * 3_600_000L + 60_000))
    }

    @Test fun timerFinishesOnlyWhenResumedAtOrAfterEnd() {
        assertEquals(RestTimerTick(1000), restTimerTick(1000, 999, driving = false, resumed = true))
        assertEquals(RestTimerTick(1000), restTimerTick(1000, 5000, driving = false, resumed = false))
        assertEquals(RestTimerTick(null), restTimerTick(null, 5000, driving = false, resumed = true))
        assertEquals(RestTimerTick(null, finished = true, notify = true), restTimerTick(1000, 5000, driving = false, resumed = true))
        assertEquals(RestTimerTick(null), restTimerTick(null, 5000, driving = true, resumed = false))
    }
}

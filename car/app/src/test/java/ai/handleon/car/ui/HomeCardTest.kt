package ai.handleon.car.ui

import ai.handleon.car.collect.DriveSessionView
import ai.handleon.car.engine.Mode
import ai.handleon.car.sync.patternTrip
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class HomeCardTest {
    private val day = LocalDate.parse("2026-10-25")
    private val note = CarNote(1, "큰딸", "천천히 오세요", "2026-10-25T00:00:00Z")
    @Test fun datesCycleWithinMode() {
        for ((mode, size) in listOf(Mode.MAEUM to 7, Mode.CONDITION to 6)) {
            assertEquals(size, (0L until size).map { todayLine(day.plusDays(it), mode) }.distinct().size)
            assertEquals(todayLine(day, mode), todayLine(day.plusDays(size.toLong()), mode))
        }
        assertNotNull(todayLine(LocalDate.ofEpochDay(-1), Mode.MAEUM))
    }
    @Test fun conditionNeverShowsCenterAndCopyAvoidsProhibitedWords() {
        val forbidden = listOf("치매", "인지장애", "진단", "위험", "점수", "질환", "판정", "우울", "예방")
        for (mode in Mode.entries) for (offset in 0L..13) {
            val line = todayLine(day.plusDays(offset), mode)
            if (mode == Mode.CONDITION) assertFalse(line.center)
            val copy = (line.title + line.body).replace("치매안심센터", "")
            forbidden.forEach { assertFalse(it in copy, copy) }
        }
    }
    @Test fun dismissalIsOnlyForTodayAndRequiresSavedTrip() {
        assertNull(visibleTodayLine(day, Mode.MAEUM, false, null))
        assertNull(visibleTodayLine(day, Mode.MAEUM, true, day.toString()))
        assertNotNull(visibleTodayLine(day.plusDays(1), Mode.MAEUM, true, day.toString()))
    }
    @Test fun savedTripUsesKoreanDateAcrossMidnight() {
        val trip = patternTrip().copy(startMs = Instant.parse("2026-10-24T15:01:00Z").toEpochMilli())
        assertTrue(hasTripToday(listOf(trip), day))
        assertFalse(hasTripToday(listOf(trip), day.minusDays(1)))
        assertFalse(hasTripToday(emptyList(), day))
    }
    @Test fun sharingMappingsMatchPhone() {
        for ((scope, label) in listOf("stage" to "단계만", "trend" to "추이 그래프까지", "detail" to "자세한 지표와 편지까지")) {
            assertEquals(label, SharingRecipient("큰딸", scope, 0, true).scopeText)
        }
        assertEquals("큰딸 · 추이 그래프까지 · 운전 패턴 함께 · 바로 공유", SharingRecipient("큰딸", "trend", 0, true).description)
        for (delay in listOf(3, 7, 14)) assertEquals("${delay}일 뒤 공유", SharingRecipient("가족", "stage", delay, false).delayText)
        assertFalse("운전 패턴" in SharingRecipient("가족", "detail", 3, false).description)
        assertFalse(SharingUi(false, emptyList()).showHome)
        assertTrue(SharingUi(true, emptyList()).showHome)
        assertEquals("다시 공유하기", SharingUi(true, emptyList()).action)
    }
    @Test fun topCardPriority() {
        val line = todayLine(day, Mode.MAEUM)
        assertIs<HomeCard.Rest>(topCard(RestUi(), note, line))
        assertIs<HomeCard.Note>(topCard(null, note, line))
        assertIs<HomeCard.Line>(topCard(null, null, line))
        assertNull(topCard(null, null, null))
    }
    @Test fun noteAcknowledgementShowsNextThenTodayLine() {
        val next = note.copy(id = 2, familyName = "아들")
        val notes = listOf(note, next)
        assertEquals("큰딸 님의 한마디", (topCard(null, notes.first(), null) as HomeCard.Note).ui.title)
        val remaining = notesAfterSeen(notes, 1)
        assertEquals(next, (topCard(null, remaining.first(), null) as HomeCard.Note).ui)
        assertEquals(notes, notesAfterSeen(notes, 99))
        assertIs<HomeCard.Line>(topCard(null, notesAfterSeen(remaining, 2).firstOrNull(), todayLine(day, Mode.MAEUM)))
    }
    @Test fun restEligibilityAndMinuteFloor() {
        val session = DriveSessionView(0, 2 * 3_600_000L)
        assertTrue(restEligible(session, session.lastMovingMs + 29 * 60_000, 2 * 3_600_000L))
        assertFalse(restEligible(session, session.lastMovingMs + 30 * 60_000, 2 * 3_600_000L))
        assertFalse(restEligible(session, session.lastMovingMs, session.continuousMs + 1))
        assertEquals("연속 운전 2시간 10분", continuousDriveText(130 * 60_000L + 59_999))
        assertEquals("연속 운전 2분", continuousDriveText(179_999))
        assertEquals("15:00", RestUi(REST_TIMER_MS).title)
        assertEquals("00:01", RestUi(1).title)
    }
    @Test fun drivingCancelsTimerEvenAtFinishAndBackgroundNeverNotifies() {
        assertEquals(RestTimerTick(null), restTimerTick(1000, 1000, driving = true, resumed = true))
        assertEquals(RestTimerTick(null), restTimerTick(1000, 500, driving = true, resumed = false))
        assertFalse(restTimerTick(1000, 2000, driving = false, resumed = false).notify)
        val finished = restTimerTick(1000, 1000, driving = false, resumed = true)
        assertTrue(finished.notify); assertTrue(finished.finished); assertNull(finished.endMs)
        assertFalse(restTimerTick(finished.endMs, 2000, driving = false, resumed = true).notify)
    }
}

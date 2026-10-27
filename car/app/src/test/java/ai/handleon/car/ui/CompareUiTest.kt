package ai.handleon.car.ui

import ai.handleon.car.sync.patternTrip
import ai.handleon.car.warmstart.BlueLinkMonth
import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test
import kotlin.test.*

class CompareUiTest {
    private val now = 1793070000000L
    private val home = LatLon(37.4, 127.1)
    private val lost = patternTrip().copy(startMs = now - 3600000, endMs = now - 1000, guided = true, familiarDest = true, reroutes = 2)
    private fun previous(minutes: Int = 100) = (7..9).map { BlueLinkMonth(YearMonth.of(2026, it), 30.0, minutes, 99) }

    @Test fun `lost way card needs a home`() {
        assertNull(lostWayCard(lost, null, now, null))
        assertEquals(lost.startMs, lostWayCard(lost, home, now, null))
        assertNull(lostWayCard(null, home, now, null))
    }
    @Test fun `lost way card expires after 30 minutes`() {
        assertEquals(lost.startMs, lostWayCard(lost.copy(endMs = now - 1800000 + 1), home, now, null))
        assertNull(lostWayCard(lost.copy(endMs = now - 1800000), home, now, null))
        assertNull(lostWayCard(lost.copy(endMs = now + 1), home, now, null))
    }
    @Test fun `lost way card requires owner car evidence and honors the dismissal`() {
        assertNull(lostWayCard(lost, home, now, lost.startMs))
        assertNull(lostWayCard(lost.copy(source = Source.TIMELINE), home, now, null))
        assertNull(lostWayCard(lost.copy(source = Source.SIM), home, now, null))
        assertNull(lostWayCard(lost.copy(guided = false), home, now, null))
        assertNull(lostWayCard(lost.copy(familiarDest = false), home, now, null))
        assertNull(lostWayCard(lost.copy(reroutes = 1), home, now, null))
    }
    @Test fun `compare says similar within 20 percent`() {
        for (current in listOf(80, 100, 120)) assertEquals("이전 차와 비슷하게 운전하세요 · 모의", blueLinkCompare(previous(), current))
        assertEquals("이전 차보다 운전 시간이 21% 줄었어요 · 모의", blueLinkCompare(previous(), 79))
        assertEquals("이전 차보다 운전 시간이 21% 늘었어요 · 모의", blueLinkCompare(previous(), 121))
    }
    @Test fun `compare is null without previous months`() {
        assertNull(blueLinkCompare(emptyList(), 100))
        assertNull(blueLinkCompare(previous().take(2), 100))
        assertNull(blueLinkCompare(previous(0), 100))
        assertNull(blueLinkCompare(previous(), null))
        assertNull(blueLinkCompare(previous(), -1))
    }
    @Test fun `compare uses the latest three distinct months and never the score`() {
        val older = BlueLinkMonth(YearMonth.of(2026, 6), 30000.0, 10000, 1)
        assertEquals("이전 차와 비슷하게 운전하세요 · 모의", blueLinkCompare((previous() + older).reversed(), 100))
        assertNull(blueLinkCompare(List(3) { previous().first() }, 100))
        assertEquals("이전 차보다 운전 시간이 100% 줄었어요 · 모의", blueLinkCompare(previous(), 0))
    }
    @Test fun `adaptation reads only presentation replay frames through the selected day`() {
        val from = LocalDate.of(2026, 10, 19)
        val start = from.atStartOfDay(KST).toInstant().toEpochMilli()
        val t = patternTrip().copy(source = Source.SIM, startMs = start, endMs = start + 3600000, distanceKm = 50.0, lkaEvents = 3, fcaEvents = 2)
        val trips = listOf(t, t.copy(source = Source.CAR), t.copy(source = Source.TIMELINE), t.copy(startMs = start + 7 * 86400000, endMs = start + 7 * 86400000 + 3600000))
        assertTrue(replayAdaptation(trips, from, presentation = false).isEmpty())
        val weeks = replayAdaptation(trips, from, presentation = true)
        assertEquals(1, weeks.size)
        assertEquals(10.0, weeks.single().per100km)
        assertEquals(50.0, weeks.single().km)
    }
    @Test fun `adaptation keeps missing rates distinct from zero and marks mock data`() {
        val from = LocalDate.of(2026, 10, 19)
        val weeks = listOf(AdaptWeek(from, 10.0, 1, 0, null), AdaptWeek(from.plusWeeks(1), 50.0, 0, 0, 0.0))
        val bars = adaptationBars(weeks)
        assertNull(bars[0].per100km)
        assertEquals(0.0, bars[1].per100km)
        val lines = adaptationLines(weeks).joinToString(" ")
        assertContains(lines, "모의 · 경고등 읽기는 Pleos 승인 후")
        assertContains(lines, "차로 유지 보조와 전방 충돌 경고가 도와준 횟수(100km당)")
        for (word in listOf("점수", "등급", "진단", "위험", "면허 반납")) assertFalse(word in lines)
    }
}

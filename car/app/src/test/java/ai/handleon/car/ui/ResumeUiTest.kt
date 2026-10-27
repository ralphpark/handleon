package ai.handleon.car.ui

import ai.handleon.driveprint.*
import ai.handleon.car.sync.patternTrip
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.*

class ResumeUiTest {
    private val now = ZonedDateTime.parse("2026-10-27T12:00:00+09:00")
    @Test fun `resume lines never use score words`() {
        val ui = buildResume(emptyList(), emptySet(), now)
        val lines = resumeLines(ui).joinToString(" ")
        for (word in listOf("점수", "등급", "진단", "위험", "면허 반납")) assertFalse(word in lines)
        assertTrue(lines.indexOf("이번 주") < lines.indexOf("이번 달"))
        assertTrue(lines.indexOf("운전 약속") < lines.indexOf("새 차 적응"))
        assertTrue(lines.indexOf("새 차 적응") < lines.indexOf("이전 차와 비교"))
    }
    @Test fun `resume shows promises in declared order`() {
        val ui = buildResume(emptyList(), emptySet(), now).copy(promises = listOf(
            PromiseResult(Promise.NO_RAIN, 1, 2), PromiseResult(Promise.REST_2H, 2, 0), PromiseResult(Promise.NO_NIGHT, 3, 1)))
        val lines = resumeLines(ui).joinToString(" ")
        assertTrue(lines.indexOf("해 지면 운전 안 하기") < lines.indexOf("2시간마다 쉬기"))
        assertTrue(lines.indexOf("2시간마다 쉬기") < lines.indexOf("비 오는 날은 쉬기"))
    }
    @Test fun `resume excludes imported and future trips`() {
        val start = now.minusHours(2).toInstant().toEpochMilli()
        val t = patternTrip().copy(startMs = start, endMs = start + 60000)
        val ui = buildResume(listOf(t, t.copy(source = Source.TIMELINE), t.copy(startMs = start + 86400000)), emptySet(), now)
        assertEquals(1, ui.week.trips); assertEquals(1, ui.month.trips)
    }
    @Test fun `sunset line requires the selected night promise`() {
        assertNull(sunsetLine(emptySet(), null, now.toLocalDate()))
        assertTrue(sunsetLine(setOf(Promise.NO_NIGHT), null, now.toLocalDate())!!.startsWith("오늘 해 지는 시각 "))
    }
}

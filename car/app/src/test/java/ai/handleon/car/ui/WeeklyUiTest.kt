package ai.handleon.car.ui

import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test
import kotlin.test.*

class WeeklyUiTest {
    @Test fun `readiness counts down`() {
        assertEquals("주간 비교까지 2주 더 · 월간 비교는 2027년 3월부터", readinessText(Readiness(2, YearMonth.of(2027, 3), 20)))
        assertEquals("평소와 비교하고 있어요", readinessText(Readiness(0, null, 0)))
        assertFalse("null" in readinessText(Readiness(4, null, 50)))
    }
    @Test fun `weekly text only when flagged`() {
        val week = WeekResult(LocalDate.of(2026, 10, 19), setOf(Domain.MOBILITY), true, true)
        val r = WeeklyReport(WeeklyStatus.FLAGGED, listOf(week), null, 0, emptyList())
        assertEquals("요즘 몇 주 평소보다 가까운 곳만 다니셨어요", weeklyText(r))
        assertNull(weeklyText(r.copy(status = WeeklyStatus.OK)))
        assertNull(weeklyText(r.copy(status = WeeklyStatus.BUILDING)))
        assertEquals("요즘 몇 주 익숙한 길에서도 길안내를 더 자주 켜셨어요", weeklyText(r.copy(weeks = listOf(week.copy(over = setOf(Domain.WAYFINDING))))))
        assertEquals("요즘 몇 주 운전하는 모습이 평소와 조금 달랐어요", weeklyText(r.copy(weeks = listOf(week.copy(over = setOf(Domain.OPERATION))))))
    }
}

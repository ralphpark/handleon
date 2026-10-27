package ai.handleon.car.gleo

import ai.handleon.car.sync.patternTrip
import ai.handleon.driveprint.*
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.*

class GleoContextTest {
    @Test fun `context uses owner car facts and KST boundaries without storing raw identifiers`() {
        val now = ZonedDateTime.parse("2026-10-27T01:00:00+09:00")
        val start = now.minusMinutes(30).toInstant().toEpochMilli()
        val t = patternTrip().copy(startMs = start, endMs = start + 60000)
        val trips = listOf(t, t.copy(startMs = start - 3600000, endMs = start - 3540000),
            t.copy(source = Source.TIMELINE), t.copy(source = Source.SIM), t.copy(startMs = start + 86400000, endMs = start + 86460000))
        val ctx = buildGleoContext(trips, setOf(Promise.NO_NIGHT), emptySet(), emptySet(), now, true)
        assertTrue(ctx.driving)
        assertEquals(1, ctx.today.size)
        assertEquals(2, ctx.week!!.trips)
        assertEquals(YearMonthForTest, ctx.currentMonth)
        assertNotNull(ctx.readiness)
        assertEquals(WeeklyStatus.BUILDING, ctx.weekly)
    }
    private val YearMonthForTest = java.time.YearMonth.of(2026, 10)
}

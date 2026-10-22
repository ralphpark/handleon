package ai.handleon.driveprint

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.*

class SunTest {
    private val KST = ZoneId.of("Asia/Seoul")
    private val SEOUL = LatLon(37.5665, 126.9780)
    private fun kst(value: String) = LocalDateTime.parse(value).atZone(KST).toInstant().toEpochMilli()
    private fun assertNear(expected: Long, actual: Long, toleranceMs: Long = 4 * 60_000L) =
        assertTrue(abs(expected - actual) <= toleranceMs, "Expected $expected, actual $actual, error ${actual - expected} ms")

    @Test fun seoulSummerSolstice() {
        assertNear(kst("2026-06-21T05:11"), Sun.sunriseMs(SEOUL, LocalDate.of(2026, 6, 21)))
        assertNear(kst("2026-06-21T19:57"), Sun.sunsetMs(SEOUL, LocalDate.of(2026, 6, 21)))
    }
    @Test fun seoulWinterSolstice() {
        assertNear(kst("2026-12-21T07:43"), Sun.sunriseMs(SEOUL, LocalDate.of(2026, 12, 21)))
        assertNear(kst("2026-12-21T17:17"), Sun.sunsetMs(SEOUL, LocalDate.of(2026, 12, 21)))
    }
    @Test fun sixPmInDecemberIsNightButNotInJune() {
        assertTrue(Sun.isNight(SEOUL, kst("2026-12-21T18:00"), KST))
        assertFalse(Sun.isNight(SEOUL, kst("2026-06-21T18:00"), KST))
    }
}

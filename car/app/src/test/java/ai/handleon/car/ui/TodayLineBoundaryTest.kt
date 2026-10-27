package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.driveprint.Source
import ai.handleon.driveprint.TripFeatures
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class TodayLineBoundaryTest {
    private val day = LocalDate.parse("2026-10-25")
    private fun tripAt(instant: String): TripFeatures {
        val start = Instant.parse(instant).toEpochMilli()
        return TripFeatures(start, start + 60_000, Source.CAR, 10.0, false, false, false,
            null, null, false, false, 0.0, 0, 0, 0, 0, 0, null, 0, null, null, 0, 0)
    }

    @Test fun maeumShowsCenterCardEverySeventhDayOnly() {
        val days = (0L until 14).map { LocalDate.parse("2026-10-15").plusDays(it) }
        assertEquals(listOf("2026-10-21", "2026-10-28"),
            days.filter { todayLine(it, Mode.MAEUM).center }.map { it.toString() })
        assertTrue(days.none { todayLine(it, Mode.CONDITION).center })
    }

    @Test fun conditionCycleIsMaeumCycleWithoutCenterCard() {
        val maeum = (0L until 7).map { todayLine(LocalDate.ofEpochDay(it), Mode.MAEUM) }
        val condition = (0L until 6).map { todayLine(LocalDate.ofEpochDay(it), Mode.CONDITION) }
        assertEquals(maeum.filterNot { it.center }, condition)
        assertTrue(maeum.last().center)
        assertEquals(1, maeum.count { it.center })
    }

    @Test fun daysBeforeEpochWrapIntoTheCycle() {
        assertEquals(todayLine(LocalDate.ofEpochDay(6), Mode.MAEUM), todayLine(LocalDate.ofEpochDay(-1), Mode.MAEUM))
        assertEquals(todayLine(LocalDate.ofEpochDay(0), Mode.MAEUM), todayLine(LocalDate.ofEpochDay(-7), Mode.MAEUM))
        assertEquals(todayLine(LocalDate.ofEpochDay(5), Mode.CONDITION), todayLine(LocalDate.ofEpochDay(-1), Mode.CONDITION))
        assertEquals(todayLine(LocalDate.ofEpochDay(0), Mode.CONDITION), todayLine(LocalDate.ofEpochDay(-6), Mode.CONDITION))
    }

    @Test fun tripTodayUsesExactSeoulMidnight() {
        assertTrue(hasTripToday(listOf(tripAt("2026-10-24T15:00:00Z")), day))      // 10-25 00:00 KST
        assertFalse(hasTripToday(listOf(tripAt("2026-10-24T14:59:59.999Z")), day)) // 10-24 23:59:59.999 KST
        assertTrue(hasTripToday(listOf(tripAt("2026-10-25T14:59:59.999Z")), day))  // 10-25 23:59:59.999 KST
        assertFalse(hasTripToday(listOf(tripAt("2026-10-25T15:00:00Z")), day))     // 10-26 00:00 KST
    }

    @Test fun visibleLineIsTodaysLineUntilDismissedToday() {
        for (mode in Mode.entries) {
            assertEquals(todayLine(day, mode), visibleTodayLine(day, mode, true, null))
            assertEquals(todayLine(day, mode), visibleTodayLine(day, mode, true, "2026-10-24"))
            assertEquals(todayLine(day, mode), visibleTodayLine(day, mode, true, "2026-10-26"))
            assertNull(visibleTodayLine(day, mode, true, "2026-10-25"))
            assertNull(visibleTodayLine(day, mode, false, null))
        }
    }
}

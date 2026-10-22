package ai.handleon.driveprint

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class FamiliarTest {
    private val now = LocalDate.of(2026, 10, 22)
    private fun trip(day: LocalDate, cell: Long?) = TripFeatures(
        day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(), 0, Source.CAR,
        10.0, false, false, false, cell, null, false, false, 0.0,
        0, 0, 0, 0, 0, null, 0, null, null, 0, 0)

    @Test fun familiarNeedsFiveVisitsInSixMonths() {
        val recent = List(5) { trip(now.minusDays(it.toLong()), 1) } + List(4) { trip(now, 2) }
        val old = List(10) { trip(now.minusMonths(6).minusDays(1), 2) }
        val future = List(5) { trip(now.plusDays(1), 3) }
        assertEquals(setOf(1L), Familiar.from(recent + old + future + trip(now, null), now))
        assertEquals(setOf(4L), Familiar.from(List(5) { trip(now.minusMonths(6), 4) }, now))
    }
}

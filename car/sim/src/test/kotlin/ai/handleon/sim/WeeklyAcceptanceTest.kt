package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.test.*

class WeeklyAcceptanceTest {
    private fun weeklyFlags(trips: List<TripFeatures>, today: LocalDate = LocalDate.of(2026, 11, 2)) =
        Driveprint().weeklyReport(trips, emptySet(), emptySet(), today).weeks.filter { it.flagged }.map { it.weekStart }

    @Test fun `stable drivers rarely flag`() {
        // Same normal-driver parameter distribution and seed stream as CohortRun.
        val random = Random(7)
        val start = LocalDate.of(2024, 11, 4)
        val end = start.plusWeeks(52)
        val counts = (0 until 200).map { id ->
            val persona = Persona(id, null, 30 + random.nextDouble() * 40, 8 + random.nextInt(13),
                10 + random.nextDouble() * 30, random.nextLong())
            weeklyFlags(Generator.trips(persona, CohortRun.START, 13), end).count { it >= start && it < end }
        }
        val rate = counts.sum() / (200.0 * 52 / 26)
        println("weekly stable: drivers=200 weeks=52 flags=${counts.sum()} flagsPerPerson26Weeks=$rate scale=${WeeklyTrack.THRESHOLD_SCALE}")
        assertTrue(rate <= 1.0, "flags per person/26 weeks = $rate")
    }

    @Test fun `sudden drop is flagged within three weeks`() {
        val onset = LocalDate.of(2026, 6, 1)
        val first = weeklyFlags(Personas.sudden()).firstOrNull { it >= onset }
        assertNotNull(first, "No sudden-change flag")
        assertTrue(ChronoUnit.WEEKS.between(onset, first) <= 3)
        // A week can only be reported after it closes, so check the availability date too.
        assertTrue(ChronoUnit.WEEKS.between(onset, first.plusWeeks(1)) <= 3)
        println("weekly sudden: onset=$onset firstWeek=$first available=${first.plusWeeks(1)}")
    }

    @Test fun `sudden persona preserves earlier trips and halves destination range`() {
        val start = YearMonth.of(2025, 11)
        val onset = LocalDate.of(2026, 6, 1)
        val zone = java.time.ZoneId.of("Asia/Seoul")
        fun day(t: TripFeatures) = java.time.Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()
        val normal = Generator.trips(Persona(5, null, 60.0, 12, 30.0, 5), start, 12)
        val sudden = Personas.sudden()
        assertEquals(normal.filter { day(it) < onset }, sudden.filter { day(it) < onset })
        assertEquals(sudden, Personas.sudden())
        assertTrue(sudden.all { day(it) >= start.atDay(1) && day(it) < start.plusMonths(12).atDay(1) })
        val after = sudden.filter { day(it) >= onset }
        assertTrue(after.mapNotNull { it.endCell }.toSet().size <= 6)
        assertTrue(after.all { it.maxFromHomeKm!! <= 45.0 })
        assertTrue(after.size < normal.count { day(it) >= onset } * .7)
    }
}

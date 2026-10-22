package ai.handleon.driveprint

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Random
import kotlin.test.*

class DriveprintTest {
    private val KST = ZoneId.of("Asia/Seoul")
    private object SyntheticTrips {
        fun decliningDriver(months: Int, onsetMonth: Int, seed: Long): List<TripFeatures> {
            val random = Random(seed)
            return (0 until months).flatMap { offset ->
                val month = YearMonth.of(2026, 1).plusMonths(offset.toLong())
                val decline = offset + 1 >= onsetMonth
                val count = if (decline) 30 else 60
                List(count) { i ->
                    val start = month.atDay(i % month.lengthOfMonth() + 1).atTime(12, i % 60)
                        .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
                    val guided = i % (if (decline) 2 else 10) == 0
                    TripFeatures(start, start + 600_000, Source.SIM, 10.0,
                        !decline && i % 5 == 0, false, false, (i % if (decline) 4 else 12).toLong(),
                        (if (decline) 10.0 else 30.0) + random.nextDouble() * 0.1,
                        true, guided, if (guided) 10.0 else 0.0, if (guided) (if (decline) 3 else 1) else 0,
                        if (decline) 2 else 4, if (decline) 8 else 6, 5, 0,
                        0.1, 0, 1.5, 2.0, 0, 0)
                }
            }
        }
    }
    @Test fun facadeReportsOnsetForSimulatedDecline() {
        val trips = SyntheticTrips.decliningDriver(months = 18, onsetMonth = 10, seed = 11)
        val r = Driveprint().longReport(trips, emptySet(), emptySet(), YearMonth.of(2027, 6))
        assertEquals(Stage.TALK, r.stage); assertTrue(r.onsetWeeksAgo!! in 20..40)
    }
    @Test fun labeledDaysAreRemovedBeforeScoring() {
        val trips = SyntheticTrips.decliningDriver(18, 99, 11)
        val labels = (15..21).map { LocalDate.of(2027, 6, it) }.toSet()
        val withCold = trips.map { t ->
            if (java.time.Instant.ofEpochMilli(t.startMs).atZone(KST).toLocalDate() in labels)
                t.copy(reroutes = 100, guided = true, guidedKm = 1.0, steeringStd = 50.0) else t
        }
        val actual = Driveprint().longReport(withCold, labels, emptySet(), YearMonth.of(2027, 6))
        val filtered = withCold.filter { java.time.Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() !in labels }
        assertEquals(Driveprint().longReport(filtered, emptySet(), emptySet(), YearMonth.of(2027, 6)), actual)
        assertEquals(Stage.STABLE, actual.stage)
    }
    @Test fun facadeIgnoresFutureTripsAndTimelineForCondition() {
        val trips = SyntheticTrips.decliningDriver(18, 10, 11)
        val today = LocalDate.of(2026, 1, 2)
        val engine = Driveprint()
        assertEquals(ConditionStatus.BUILDING, engine.conditionReport(
            trips.map { it.copy(source = Source.TIMELINE) }, emptySet(), emptySet(), today).status)
        assertTrue(engine.conditionReport(trips, emptySet(), emptySet(), today).days.all { it.day <= today })
        assertEquals(Stage.BUILDING, engine.longReport(trips, emptySet(), emptySet(), YearMonth.of(2026, 3)).stage)
    }
    @Test fun emptyFacadesAreBuilding() {
        assertEquals(Stage.BUILDING, Driveprint().longReport(emptyList(), emptySet(), emptySet(), YearMonth.of(2026, 10)).stage)
        assertEquals(ConditionStatus.BUILDING, Driveprint().conditionReport(emptyList(), emptySet(), emptySet(), LocalDate.of(2026, 10, 22)).status)
    }
}

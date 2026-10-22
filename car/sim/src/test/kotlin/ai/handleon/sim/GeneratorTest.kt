package ai.handleon.sim

import ai.handleon.driveprint.Source
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.*

class GeneratorTest {
    private val START = YearMonth.of(2024, 11)
    private fun p(seed: Long = 7, onset: YearMonth? = null) = Persona(1, onset, 60.0, 12, 30.0, seed)
    private fun countIn(trips: List<ai.handleon.driveprint.TripFeatures>, month: YearMonth) =
        trips.count { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(ZoneId.of("Asia/Seoul"))) == month }
    @Test fun generatorIsDeterministic() = assertEquals(Generator.trips(p(seed = 1), START, 24), Generator.trips(p(seed = 1), START, 24))
    @Test fun declinePersonaLosesTrips() {
        val t = Generator.trips(p(onset = START.plusMonths(10)), START, 24)
        assertTrue(countIn(t, START.plusMonths(20)) < countIn(t, START.plusMonths(5)) * 0.9)
    }
    @Test fun generatedTripsAreChronologicalSimTripsAndPrefixStable() {
        val full = Generator.trips(p(), START, 24)
        assertTrue(full.all { it.source == Source.SIM && it.endMs > it.startMs && it.distanceKm > 0 })
        assertTrue(full.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertEquals(Generator.trips(p(), START, 12), full.filter {
            Instant.ofEpochMilli(it.startMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate() < START.plusMonths(12).atDay(1)
        })
    }
}

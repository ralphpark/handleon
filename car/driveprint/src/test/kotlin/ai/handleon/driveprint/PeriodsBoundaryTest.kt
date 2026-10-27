package ai.handleon.driveprint

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class PeriodsBoundaryTest {
    private val KST = ZoneId.of("Asia/Seoul")
    private val march = d("2026-03-01")
    private val april = d("2026-04-01")
    private fun d(text: String) = LocalDate.parse(text)
    private fun kst(text: String) = LocalDateTime.parse(text).atZone(KST).toInstant().toEpochMilli()
    private fun tripAt(start: Long = kst("2026-03-02T12:00"), km: Double = 10.0, cell: Long? = 1L) = TripFeatures(
        startMs = start, endMs = start + 30 * 60_000L, source = Source.CAR,
        distanceKm = km, night = false, rain = false, offDay = false, endCell = cell,
        maxFromHomeKm = 8.0, familiarDest = true, guided = true, guidedKm = 10.0,
        reroutes = 1, leftTurns = 2, rightTurns = 3, announcedTurns = 5, signalMisses = 1,
        speedingRatio = 0.2, hardBrakes = 1, steeringStd = 2.0, steeringReversalsPerMin = 6.0,
        lkaEvents = 2, fcaEvents = 0,
    )

    @Test fun excludedDayShrinksScaleOnlyInsidePeriod() {
        val trip = listOf(tripAt())
        assertEquals(30.0 / 31, Periods.values(trip, march, april, KST).getValue(FeatureId.M1), 1e-12)
        assertEquals(1.0, Periods.values(trip, march, april, KST, excludedDays = setOf(d("2026-03-31"))).getValue(FeatureId.M1), 1e-12)
        assertEquals(30.0 / 31, Periods.values(trip, march, april, KST,
            excludedDays = setOf(d("2026-02-28"), april)).getValue(FeatureId.M1), 1e-12)
    }

    @Test fun tripOnExcludedDayIsDropped() {
        val labeled = setOf(d("2026-03-02"))
        assertTrue(Periods.values(listOf(tripAt()), march, april, KST, excludedDays = labeled).isEmpty())
        assertEquals(mapOf(FeatureId.M1 to 0.0, FeatureId.M2 to 0.0, FeatureId.M3 to 0.0),
            Periods.values(listOf(tripAt()), march, april, KST, excludedDays = labeled, active = true))
    }

    @Test fun activePeriodWithoutTripsHasOnlyZeroCounts() {
        val zeros = mapOf(FeatureId.M1 to 0.0, FeatureId.M2 to 0.0, FeatureId.M3 to 0.0)
        assertEquals(zeros, Periods.values(emptyList(), march, april, KST, active = true))
        assertEquals(zeros, Periods.values(listOf(tripAt(kst("2026-04-01T00:00"))), march, april, KST, active = true))
    }

    @Test fun fullyExcludedPeriodIsEmptyEvenWhenActive() {
        val all = (0L until 31).map { march.plusDays(it) }.toSet()
        assertTrue(Periods.values(listOf(tripAt()), march, april, KST, excludedDays = all, active = true).isEmpty())
    }

    @Test fun reversedPeriodIsRejected() {
        assertFailsWith<IllegalArgumentException> { Periods.values(emptyList(), april, march, KST) }
    }

    @Test fun periodEdgesAreSeoulMidnights() {
        val first = tripAt(kst("2026-03-01T00:00"))
        val last = tripAt(kst("2026-03-31T23:59:59.999"))
        val next = tripAt(kst("2026-04-01T00:00"))
        assertEquals(2.0 * 30 / 31, Periods.values(listOf(first, last, next), march, april, KST).getValue(FeatureId.M1), 1e-12)
    }

    @Test fun singleDayPeriodScalesCountsToThirtyDays() {
        val v = Periods.values(listOf(tripAt()), d("2026-03-02"), d("2026-03-03"), KST)
        assertEquals(30.0, v.getValue(FeatureId.M1), 1e-12)
        assertEquals(1.0, v.getValue(FeatureId.M2), 1e-12)
        assertEquals(30.0, v.getValue(FeatureId.M3), 1e-12)
    }

    @Test fun singlePlaceHasZeroEntropy() {
        val v = Periods.values(List(5) { tripAt() }, march, april, KST)
        assertEquals(1.0, v.getValue(FeatureId.M5))
        assertEquals(0.0, v.getValue(FeatureId.M6), 1e-12)
    }

    @Test fun outingBandStartsAtEightKm() {
        val v = Periods.values(listOf(tripAt(km = 7.999), tripAt(km = 8.0)), march, april, KST)
        assertEquals(1.0 * 30 / 31, v.getValue(FeatureId.M3), 1e-12)
    }
}

package ai.handleon.driveprint

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class PeriodsTest {
    private val KST = ZoneId.of("Asia/Seoul")
    private fun d(text: String) = LocalDate.parse(text)
    private fun kst(text: String) = LocalDateTime.parse(text).atZone(KST).toInstant().toEpochMilli()
    private fun tripAt(start: Long = kst("2026-03-02T12:00"), durationMin: Int = 30) = TripFeatures(
        startMs = start, endMs = start + durationMin * 60_000L, source = Source.CAR,
        distanceKm = 10.0, night = false, rain = false, offDay = false, endCell = 1L,
        maxFromHomeKm = 8.0, familiarDest = true, guided = true, guidedKm = 10.0,
        reroutes = 1, leftTurns = 2, rightTurns = 3, announcedTurns = 5, signalMisses = 1,
        speedingRatio = 0.2, hardBrakes = 1, steeringStd = 2.0, steeringReversalsPerMin = 6.0,
        lkaEvents = 2, fcaEvents = 0,
    )
    private fun trips(n: Int, perDay: Int) = List(n) {
        tripAt(d("2026-03-02").plusDays((it / perDay).toLong()).atTime(12, 0).atZone(KST).toInstant().toEpochMilli())
    }
    private fun timelineTrips(n: Int) = trips(n, 2).map { it.copy(source = Source.TIMELINE) }
    private fun visits(places: Int, each: Int) = List(places * each) { tripAt().copy(endCell = (it / each).toLong()) }
    private fun values(trips: List<TripFeatures>) = Periods.values(trips, d("2026-03-01"), d("2026-04-01"), KST)

    @Test fun countsAreScaledToThirtyDays() = assertEquals(30.0, Periods.values(trips(n = 7, perDay = 1), d("2026-03-02"), d("2026-03-09"), KST)[FeatureId.M1]!!, 1e-9)
    @Test fun tripStartingAt2350KstOnMarch31BelongsToMarch() {
        val t = tripAt(kst("2026-03-31T23:50"), durationMin = 30)
        assertEquals(1.0 * 30 / 31, Periods.values(listOf(t), d("2026-03-01"), d("2026-04-01"), KST)[FeatureId.M1]!!, 1e-9)
        assertNull(Periods.values(listOf(t), d("2026-04-01"), d("2026-05-01"), KST)[FeatureId.M1])
    }
    @Test fun timelineTripsFeedMobilityOnly() {
        val v = Periods.values(timelineTrips(40), d("2026-03-01"), d("2026-04-01"), KST)
        assertTrue(FeatureId.M5 in v); assertFalse(FeatureId.W4 in v); assertFalse(FeatureId.O2 in v)
    }
    @Test fun nightAndRainTripsAreExcludedFromOperation() {
        for (excluded in listOf(trips(4, 1).map { it.copy(night = true) }, trips(4, 1).map { it.copy(rain = true) })) {
            val v = values(excluded)
            assertTrue(FeatureId.M1 in v)
            listOf(FeatureId.O1, FeatureId.O2, FeatureId.O7, FeatureId.O8, FeatureId.O9).forEach { assertFalse(it in v) }
        }
    }
    @Test fun missingSignalsGiveMissingFeatureNotZero() {
        val v = values(trips(4, 1).map { it.copy(speedingRatio = null, steeringStd = null) })
        assertFalse(FeatureId.O1 in v)
        assertFalse(FeatureId.O7 in v)
    }
    @Test fun entropyOfFourEqualPlacesIsTwoBits() = assertEquals(2.0, Periods.values(visits(4, each = 5), d("2026-03-01"), d("2026-04-01"), KST)[FeatureId.M6]!!, 1e-9)

    @Test fun koreanMidnightDoesNotUseUtcDate() {
        val t = tripAt(kst("2026-04-01T00:10"))
        assertTrue(values(listOf(t)).isEmpty())
        assertEquals(1.0, Periods.values(listOf(t), d("2026-04-01"), d("2026-05-01"), KST)[FeatureId.M1])
    }
    @Test fun missingLocationAndHomeDoNotBecomeZero() {
        val v = values(listOf(tripAt().copy(endCell = null, maxFromHomeKm = null)))
        assertFalse(FeatureId.M5 in v)
        assertFalse(FeatureId.M6 in v)
        assertFalse(FeatureId.M7 in v)
    }
    @Test fun operationRatesRespectExposureAndExcludeTimeline() {
        val raw = tripAt().copy(distanceKm = 20.0, hardBrakes = 2, lkaEvents = 3, signalMisses = 2)
        val v = values(listOf(raw, raw.copy(source = Source.TIMELINE, distanceKm = 1000.0, hardBrakes = 100)))
        assertEquals(10.0, v[FeatureId.O2]!!, 1e-9)
        assertEquals(15.0, v[FeatureId.O9]!!, 1e-9)
        assertEquals(0.4, v[FeatureId.O8]!!, 1e-9)
        val short = values(listOf(raw.copy(distanceKm = 19.99, announcedTurns = 4)))
        assertFalse(FeatureId.O2 in short)
        assertFalse(FeatureId.O9 in short)
        assertFalse(FeatureId.O8 in short)
    }
    @Test fun wayfindingMinimumExposuresAndRatios() {
        val raw = tripAt().copy(leftTurns = 4, rightTurns = 6, guidedKm = 5.0, reroutes = 2)
        val v = values(listOf(raw))
        assertEquals(0.4, v[FeatureId.W2]!!, 1e-9)
        assertEquals(0.4, v[FeatureId.W3]!!, 1e-9)
        assertFalse(FeatureId.W4 in v)
        val short = values(listOf(raw.copy(leftTurns = 3, guidedKm = 4.99)))
        assertFalse(FeatureId.W2 in short)
        assertFalse(FeatureId.W3 in short)
        assertEquals(2.0 / 3, values(listOf(raw, raw, raw.copy(guided = false)))[FeatureId.W4]!!, 1e-9)
    }
    @Test fun unfamiliarDestinationsDoNotFeedFamiliarRouteMetrics() {
        val v = values(trips(5, 1).map { it.copy(familiarDest = false) })
        assertFalse(FeatureId.W3 in v)
        assertFalse(FeatureId.W4 in v)
        assertTrue(FeatureId.W2 in v)
    }
    @Test fun mediansIgnoreMissingValuesAndSimIsIncluded() {
        val v = values(listOf(
            tripAt().copy(source = Source.SIM, speedingRatio = 0.1, steeringStd = 1.0),
            tripAt().copy(speedingRatio = null, steeringStd = null),
            tripAt().copy(speedingRatio = 0.3, steeringStd = 3.0),
        ))
        assertEquals(0.2, v[FeatureId.O1]!!, 1e-9)
        assertEquals(2.0, v[FeatureId.O7]!!, 1e-9)
    }
    @Test fun mobilityCountsRatiosAndDistanceBoundaries() {
        val raw = listOf(tripAt().copy(distanceKm = 8.0, endCell = 1), tripAt().copy(distanceKm = 15.999, endCell = 2, night = true),
            tripAt().copy(distanceKm = 16.0, endCell = 2, maxFromHomeKm = 20.0))
        val v = values(raw)
        assertEquals(2.0 * 30 / 31, v[FeatureId.M3]!!, 1e-9)
        assertEquals(1.0 / 31, v[FeatureId.M2]!!, 1e-9)
        assertEquals(1.0 / 3, v[FeatureId.M4]!!, 1e-9)
        assertEquals(2.0, v[FeatureId.M5]!!, 1e-9)
        assertEquals(20.0, v[FeatureId.M7]!!, 1e-9)
    }
    @Test fun emptyPeriodHasNoValuesAndInvalidRangeIsRejected() {
        assertTrue(values(emptyList()).isEmpty())
        assertFailsWith<IllegalArgumentException> { Periods.values(emptyList(), d("2026-03-01"), d("2026-03-01"), KST) }
    }
}

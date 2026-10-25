package ai.handleon.driveprint

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PatternsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.parse("2026-10-25")
    private fun trip(date: String = "2026-10-01T12:00", minutes: Double = 20.0, night: Boolean = false,
                     km: Double = 10.0, cell: Long? = 1, radius: Double? = 8.0): TripFeatures {
        val start = LocalDateTime.parse(date).atZone(zone).toInstant().toEpochMilli()
        return TripFeatures(start, start + (minutes * 60_000).toLong(), Source.CAR, km, night, false, false,
            cell, radius, false, false, 0.0, 0, 0, 0, 0, 0, null, 0, null, null, 0, 0)
    }
    private fun report(baselines: Map<FeatureId, Baseline> = emptyMap(), window: BaselineWindow? = null) =
        LongReport(Stage.BUILDING, emptyList(), emptyMap(), baselines, emptyList(), null,
            baselineWindows = window?.let { mapOf(FeatureId.M1 to it) }.orEmpty())

    @Test fun countsMinutesDaysAndRatios() {
        val row = Patterns.monthly(listOf(trip(minutes = 20.2), trip("2026-10-01T20:00", minutes = 20.2, night = true),
            trip("2026-10-02T10:00", minutes = 20.2, km = 35.0)), null, zone, today).single()
        assertEquals(3, row.trips); assertEquals(61, row.driveMinutes); assertEquals(2, row.driveDays)
        assertEquals(0.333, row.nightRatio); assertEquals(0.333, row.longRatio)
    }

    @Test fun partialOnlyForCurrentMonthAndNoMonthsBeforeFirstTrip() {
        val rows = Patterns.monthly(listOf(trip("2026-08-31T23:59")), null, zone, today)
        assertEquals(listOf("2026-10", "2026-09", "2026-08"), rows.map { it.month.toString() })
        assertEquals(listOf(true, false, false), rows.map { it.partial })
        assertEquals(1, rows.last().trips) // A trip crossing midnight stays in its start month.
        assertEquals(2, Patterns.monthly(listOf(trip("2026-01-01T12:00")), null, zone, today, 2).size)
    }

    @Test fun emptyMonthAfterFirstTripIsZeroWithNullRatios() {
        val row = Patterns.monthly(listOf(trip("2026-08-01T12:00")), null, zone, today)[1]
        assertEquals(0, row.trips); assertEquals(0, row.driveMinutes); assertEquals(0, row.driveDays)
        assertNull(row.nightRatio); assertNull(row.longRatio); assertNull(row.radiusRatio); assertNull(row.placesRatio)
        assertTrue(Patterns.monthly(emptyList(), null, zone, today).isEmpty())
    }

    @Test fun ratiosAgainstBaselineAndClampedTo20() {
        val r = report(mapOf(FeatureId.M7 to Baseline(3.0, 1.0), FeatureId.M5 to Baseline(2.0, 1.0)))
        val row = Patterns.monthly(listOf(trip(radius = 4.0), trip(radius = 10.0, cell = 2)), r, zone, today).single()
        assertEquals(3.133, row.radiusRatio); assertEquals(1.0, row.placesRatio)
        val small = report(mapOf(FeatureId.M7 to Baseline(0.1, 1.0), FeatureId.M5 to Baseline(0.01, 1.0)))
        val clamped = Patterns.monthly(listOf(trip()), small, zone, today).single()
        assertEquals(20.0, clamped.radiusRatio); assertEquals(20.0, clamped.placesRatio)
    }

    @Test fun nullWithoutBaseline() {
        for (r in listOf(null, report(), report(mapOf(FeatureId.M7 to Baseline(0.0, 1.0), FeatureId.M5 to Baseline(-1.0, 1.0))))) {
            val row = Patterns.monthly(listOf(trip()), r, zone, today).single()
            assertNull(row.radiusRatio); assertNull(row.placesRatio)
            assertNull(row.usualTrips); assertNull(row.usualMinutes); assertNull(row.usualNightRatio); assertNull(row.usualLongRatio)
        }
        val row = Patterns.monthly(listOf(trip(cell = null, radius = null)),
            report(mapOf(FeatureId.M7 to Baseline(1.0, 1.0), FeatureId.M5 to Baseline(1.0, 1.0))), zone, today).single()
        assertNull(row.radiusRatio); assertNull(row.placesRatio)
    }

    @Test fun usualIsMedianOverBaselineWindow() {
        val trips = listOf(trip("2026-07-01T12:00", minutes = 10.0, night = true, km = 30.0)) +
            List(3) { trip("2026-08-01T12:00", minutes = 20.0, night = it == 0, km = if (it == 0) 30.0 else 29.999) } +
            List(10) { trip(minutes = 100.0) }
        // September has zero trips; October is partial and must not influence the median.
        val window = BaselineWindow(YearMonth.of(2026, 7), YearMonth.of(2026, 11))
        val row = Patterns.monthly(trips, report(window = window), zone, today).first()
        assertEquals(1, row.usualTrips); assertEquals(10, row.usualMinutes)
        assertEquals(0.667, row.usualNightRatio); assertEquals(0.667, row.usualLongRatio)
        val even = Patterns.monthly(trips, report(window = window.copy(toExclusive = YearMonth.of(2026, 9))), zone, today).first()
        assertEquals(2, even.usualTrips); assertEquals(35, even.usualMinutes)
    }

    @Test fun serializationHasOnlyAggregatesAndMonthString() {
        val row = Patterns.monthly(listOf(trip()), null, zone, today).single()
        val json = Json.encodeToString(row)
        assertTrue("\"month\":\"2026-10\"" in json)
        for (key in listOf("distanceKm", "endCell", "maxFromHomeKm", "lat", "lon")) assertFalse("\"$key\"" in json)
    }
}

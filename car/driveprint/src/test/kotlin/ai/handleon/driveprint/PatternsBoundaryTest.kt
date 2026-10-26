package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PatternsBoundaryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.parse("2026-10-25")
    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun trip(startMs: Long = at("2026-10-01T12:00"), durationMs: Long = 20 * 60_000L, night: Boolean = false,
                     km: Double = 10.0, cell: Long? = 1, radius: Double? = 8.0) =
        TripFeatures(startMs, startMs + durationMs, Source.CAR, km, night, false, false,
            cell, radius, false, false, 0.0, 0, 0, 0, 0, 0, null, 0, null, null, 0, 0)
    private fun report(baselines: Map<FeatureId, Baseline> = emptyMap(), windows: Map<FeatureId, BaselineWindow> = emptyMap()) =
        LongReport(Stage.BUILDING, emptyList(), emptyMap(), baselines, emptyList(), null, baselineWindows = windows)
    private fun usualWindow(from: YearMonth, toExclusive: YearMonth) = mapOf(FeatureId.M1 to BaselineWindow(from, toExclusive))
    private fun names(rows: List<PatternMonth>) = rows.map { it.month.toString() }

    @Test fun monthBoundaryIsSeoulMidnightNotUtc() {
        val lastSecondOfSeptember = Instant.parse("2026-09-30T14:59:59Z").toEpochMilli() // 09-30 23:59:59 KST
        val octoberMidnight = Instant.parse("2026-09-30T15:00:00Z").toEpochMilli()       // 10-01 00:00 KST
        val trips = listOf(trip(lastSecondOfSeptember), trip(octoberMidnight))
        val rows = Patterns.monthly(trips, null, zone, today)
        assertEquals(listOf("2026-10", "2026-09"), names(rows))
        assertEquals(listOf(1, 1), rows.map { it.trips })
        assertEquals(listOf(1, 1), rows.map { it.driveDays })
        // The same instants are both September 30 in UTC.
        assertEquals(listOf(0, 2), Patterns.monthly(trips, null, ZoneOffset.UTC, today).map { it.trips })
    }

    @Test fun firstTripAtSeoulMonthStartExcludesEarlierMonths() {
        val rows = Patterns.monthly(listOf(trip(at("2026-09-01T00:00"))), null, zone, today)
        assertEquals(listOf("2026-10", "2026-09"), names(rows))
    }

    @Test fun tripCrossingMonthEndKeepsAllMinutesInStartMonth() {
        val rows = Patterns.monthly(listOf(trip(at("2026-09-30T23:50"), durationMs = 30 * 60_000L)), null, zone, today)
        assertEquals(listOf(0, 1), rows.map { it.trips })
        assertEquals(listOf(0, 30), rows.map { it.driveMinutes })
        assertEquals(listOf(0, 1), rows.map { it.driveDays })
    }

    @Test fun nonPositiveMonthCountGivesNoRowsAndOneGivesCurrentOnly() {
        val trips = listOf(trip(at("2026-08-10T12:00")), trip())
        assertTrue(Patterns.monthly(trips, null, zone, today, months = 0).isEmpty())
        assertTrue(Patterns.monthly(trips, null, zone, today, months = -1).isEmpty())
        assertEquals(listOf("2026-10"), names(Patterns.monthly(trips, null, zone, today, months = 1)))
    }

    @Test fun currentMonthWithoutTripsYetIsPartialZeroRow() {
        val rows = Patterns.monthly(listOf(trip()), null, zone, LocalDate.parse("2026-11-01"))
        assertEquals(listOf("2026-11", "2026-10"), names(rows))
        assertEquals(listOf(true, false), rows.map { it.partial })
        val current = rows.first()
        assertEquals(0, current.trips); assertEquals(0, current.driveMinutes); assertEquals(0, current.driveDays)
        assertNull(current.nightRatio); assertNull(current.longRatio)
    }

    @Test fun tripsAfterCurrentMonthAreNotCounted() {
        val future = trip(at("2026-11-02T09:00"))
        assertEquals(listOf(1), Patterns.monthly(listOf(trip(), future), null, zone, today).map { it.trips })
        assertTrue(Patterns.monthly(listOf(future), null, zone, today).isEmpty())
    }

    @Test fun minutesAreRoundedAfterSummingTripsAndHalfRoundsUp() {
        // 1.4 + 1.4 = 2.8 minutes -> 3 (rounding each trip first would give 2).
        val two = Patterns.monthly(listOf(trip(durationMs = 84_000), trip(at("2026-10-02T12:00"), durationMs = 84_000)), null, zone, today)
        assertEquals(3, two.single().driveMinutes)
        assertEquals(1, Patterns.monthly(listOf(trip(durationMs = 89_999)), null, zone, today).single().driveMinutes)
        assertEquals(2, Patterns.monthly(listOf(trip(durationMs = 90_000)), null, zone, today).single().driveMinutes)
        assertEquals(0, Patterns.monthly(listOf(trip(durationMs = 29_999)), null, zone, today).single().driveMinutes)
    }

    @Test fun longTripThresholdIsInclusiveAtThirtyKm() {
        val row = Patterns.monthly(listOf(trip(km = 30.0), trip(km = 29.999)), null, zone, today).single()
        assertEquals(0.5, row.longRatio)
    }

    @Test fun ratiosRoundHalfUpToThreeDecimals() {
        val oneOfSixteen = List(16) { trip(night = it == 0) }
        assertEquals(0.063, Patterns.monthly(oneOfSixteen, null, zone, today).single().nightRatio) // 0.0625
        val fifteenOfSixteen = List(16) { trip(night = it != 0) }
        assertEquals(0.938, Patterns.monthly(fifteenOfSixteen, null, zone, today).single().nightRatio) // 0.9375
        assertEquals(1.0, Patterns.monthly(List(3) { trip(night = true) }, null, zone, today).single().nightRatio)
        assertEquals(0.0, Patterns.monthly(List(3) { trip() }, null, zone, today).single().nightRatio)
    }

    @Test fun baselineRatioIsClampedOnlyAboveTwenty() {
        fun places(cells: Int) = Patterns.monthly(List(cells) { trip(cell = it.toLong()) },
            report(mapOf(FeatureId.M5 to Baseline(1.0, 1.0))), zone, today).single().placesRatio
        assertEquals(19.0, places(19)); assertEquals(20.0, places(20)); assertEquals(20.0, places(21))
        fun radius(km: Double) = Patterns.monthly(listOf(trip(radius = km)),
            report(mapOf(FeatureId.M7 to Baseline(0.5, 1.0))), zone, today).single().radiusRatio
        assertEquals(19.98, radius(9.99)); assertEquals(20.0, radius(10.0)); assertEquals(20.0, radius(1000.0))
    }

    @Test fun nonFiniteBaselineMedianGivesNullRatio() {
        for (median in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val row = Patterns.monthly(listOf(trip()),
                report(mapOf(FeatureId.M7 to Baseline(median, 1.0), FeatureId.M5 to Baseline(median, 1.0))), zone, today).single()
            assertNull(row.radiusRatio, "median=$median"); assertNull(row.placesRatio, "median=$median")
        }
    }

    @Test fun usualNeedsTripCountBaselineWindow() {
        val trips = listOf(trip(at("2026-07-01T12:00")), trip())
        val otherWindow = mapOf(FeatureId.M7 to BaselineWindow(YearMonth.of(2026, 7), YearMonth.of(2026, 10)))
        val row = Patterns.monthly(trips, report(windows = otherWindow), zone, today).first()
        assertNull(row.usualTrips); assertNull(row.usualMinutes); assertNull(row.usualNightRatio); assertNull(row.usualLongRatio)
    }

    @Test fun usualWindowStartingInCurrentMonthOrLaterIsEmpty() {
        val trips = listOf(trip(at("2026-07-01T12:00")), trip())
        for (from in listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11))) {
            val row = Patterns.monthly(trips, report(windows = usualWindow(from, from.plusMonths(6))), zone, today).first()
            assertNull(row.usualTrips, "from=$from"); assertNull(row.usualMinutes, "from=$from")
        }
    }

    @Test fun usualFromOnlyEmptyMonthsIsZeroWithNullRatios() {
        val trips = listOf(trip(at("2026-07-01T12:00")), trip())
        val row = Patterns.monthly(trips, report(windows = usualWindow(YearMonth.of(2026, 8), YearMonth.of(2026, 10))), zone, today).first()
        assertEquals(0, row.usualTrips); assertEquals(0, row.usualMinutes)
        assertNull(row.usualNightRatio); assertNull(row.usualLongRatio)
    }

    @Test fun usualIsTheSameOnEveryRowIncludingEmptyMonths() {
        val trips = List(2) { trip(at("2026-07-0${it + 1}T12:00")) } + List(4) { trip(at("2026-09-0${it + 1}T12:00")) }
        val rows = Patterns.monthly(trips, report(windows = usualWindow(YearMonth.of(2026, 7), YearMonth.of(2026, 10))), zone, today)
        assertEquals(listOf("2026-10", "2026-09", "2026-08", "2026-07"), names(rows))
        assertEquals(listOf(2, 2, 2, 2), rows.map { it.usualTrips }) // median(2, 0, 4)
    }

    @Test fun usualEvenMedianRoundsHalfUp() {
        val trips = listOf(trip(at("2026-07-01T12:00"), durationMs = 10 * 60_000L)) +
            List(2) { trip(at("2026-08-0${it + 1}T12:00"), durationMs = 450_000) }
        val row = Patterns.monthly(trips, report(windows = usualWindow(YearMonth.of(2026, 7), YearMonth.of(2026, 9))), zone, today).first()
        assertEquals(2, row.usualTrips)   // median(1, 2) = 1.5
        assertEquals(13, row.usualMinutes) // median(10, 15) = 12.5
    }

    @Test fun serializedRowHasOnlyAggregateFieldsAndNoRawDistances() {
        assertEquals(setOf("month", "partial", "trips", "driveMinutes", "driveDays", "nightRatio", "longRatio",
            "radiusRatio", "placesRatio", "usualTrips", "usualMinutes", "usualNightRatio", "usualLongRatio"),
            PatternMonth.serializer().descriptor.elementNames.toSet())
        val row = Patterns.monthly(listOf(trip(km = 98.765, radius = 123.456, cell = 424242)),
            report(mapOf(FeatureId.M7 to Baseline(123.456, 1.0), FeatureId.M5 to Baseline(1.0, 1.0))), zone, today).single()
        assertEquals(1.0, row.radiusRatio); assertEquals(1.0, row.placesRatio)
        val json = Json.encodeToString(row)
        for (raw in listOf("98.765", "123.456", "424242")) assertFalse(raw in json, json)
    }
}

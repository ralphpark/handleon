package ai.handleon.driveprint

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class FeaturesTest {
    @Test fun noisyStraightStillGivesSteeringStd() {
        val random = java.util.Random(73)
        val samples = wobble(3.0).map { it.copy(headingDeg = random.nextGaussian() * 3) }
        assertEquals(3.0, ex(trip(samples)).steeringStd!!, 1e-9)
    }
    @Test fun signalFiveSecondsBeforeNoisyTurnIsNotMissed() {
        val random = java.util.Random(73)
        val samples = List(90) { i -> s(speed = 30.0,
            heading = (if (i < 60) 0.0 else -minOf(i - 59, 8) * 90.0 / 8) + random.nextGaussian() * 3,
            signal = if (i in 55..75) Signal.LEFT else Signal.OFF) }
        val f = ex(trip(samples, listOf(TurnAhead(t(50), Signal.LEFT, 150.0))))
        assertEquals(1, f.announcedTurns)
        assertEquals(0, f.signalMisses)
    }
    @Test fun jitteredOneHzStillCountsAsStraightWindow() {
        val raw = trip(wobble(3.0))
        val jittered = raw.copy(samples = raw.samples.mapIndexed { i, s -> s.copy(tMs = base + i * 1003L) })
        assertEquals(3.0, ex(jittered).steeringStd!!, 1e-9)
    }
    private val KST = ZoneId.of("Asia/Seoul")
    private val base = LocalDateTime.parse("2026-05-12T12:00").atZone(KST).toInstant().toEpochMilli()
    private fun t(seconds: Int) = base + seconds * 1000L
    private fun s(speed: Double = 50.0, brake: Boolean? = false, heading: Double? = 0.0,
                  signal: Signal? = Signal.OFF, steering: Double? = 0.0, limit: Int? = 50,
                  wiper: Boolean? = false) =
        Sample(0, speed, Gear.D, brake, steering, signal, wiper, LatLon(37.5665, 126.9780), heading, limit)
    private fun cruise(speed: Double, seconds: Int, heading: Double? = 0.0, limit: Int? = 50, wiper: Int = 0) =
        List(seconds) { s(speed = speed, heading = heading, limit = limit, wiper = it < wiper) }
    private fun ramp(from: Double, to: Double, seconds: Int) =
        List(seconds) { s(speed = from + (to - from) * (it + 1) / seconds, brake = true) }
    private fun leftTurn(seconds: Int) =
        List(seconds) { s(speed = 30.0, heading = (360.0 - 90.0 * (it + 1) / seconds) % 360.0) }
    private fun wobble(amplitude: Double) =
        List(120) { s(steering = if (it % 2 == 0) amplitude else -amplitude) }
    private fun trip(samples: List<Sample>, navi: List<NaviEvent> = emptyList(), telltales: List<TelltaleOn> = emptyList()) =
        RawTrip(samples.mapIndexed { i, sample -> sample.copy(tMs = t(i)) }, navi, telltales)
    private fun ex(trip: RawTrip) = Features.extract(trip, null, emptySet(), KST)

    @Test fun hardBrakeFollowsDrivesDefinition() {
        val t = trip(cruise(50.0, 60) + listOf(s(speed = 35.0, brake = true)) + cruise(35.0, 30))
        assertEquals(1, Features.extract(t, null, emptySet(), KST).hardBrakes)
    }
    @Test fun gentleBrakeIsNotHard() = assertEquals(0, ex(trip(cruise(50.0, 60) + ramp(50.0, 30.0, 4) + cruise(30.0, 30))).hardBrakes)
    @Test fun missedSignalOnAnnouncedLeftTurn() {
        val f = ex(trip(cruise(30.0, 30, heading = 0.0) + leftTurn(seconds = 8), navi = listOf(TurnAhead(t(10), Signal.LEFT, 150.0))))
        assertEquals(1, f.announcedTurns); assertEquals(1, f.signalMisses); assertEquals(1, f.leftTurns)
    }
    @Test fun signalledTurnIsNotMissed() {
        val samples = (cruise(30.0, 30, heading = 0.0) + leftTurn(seconds = 8))
            .mapIndexed { i, sample -> if (i >= 25) sample.copy(signal = Signal.LEFT) else sample }
        val f = ex(trip(samples, navi = listOf(TurnAhead(t(10), Signal.LEFT, 150.0))))
        assertEquals(1, f.announcedTurns)
        assertEquals(0, f.signalMisses)
    }
    @Test fun turnPassedWorksWithoutHeading() {
        val f = ex(trip(cruise(30.0, 60, heading = null), navi = listOf(TurnAhead(t(10), Signal.RIGHT, 120.0), TurnPassed(t(40), Signal.RIGHT))))
        assertEquals(1, f.announcedTurns); assertEquals(1, f.signalMisses); assertEquals(1, f.rightTurns)
    }
    @Test fun recentAnnouncementMatchesTurnPassedEvenAfterInferredStart() {
        val f = ex(trip(cruise(30.0, 60, heading = null), navi = listOf(
            TurnAhead(t(35), Signal.RIGHT, 100.0), TurnPassed(t(40), Signal.RIGHT))))
        assertEquals(1, f.announcedTurns)
        assertEquals(1, f.signalMisses)
        assertEquals(1, f.rightTurns)
    }
    @Test fun speedingRatioUsesLimitPlusTen() = assertEquals(0.25, ex(trip(cruise(50.0, 90, limit = 50) + cruise(61.0, 30, limit = 50))).speedingRatio!!, 1e-9)
    @Test fun noLimitDataGivesNullNotZero() = assertNull(ex(trip(cruise(50.0, 120, limit = null))).speedingRatio)
    @Test fun steeringWobbleIsSignInvariant() = assertEquals(ex(trip(wobble(amplitude = 3.0))).steeringStd!!, ex(trip(wobble(amplitude = -3.0))).steeringStd!!, 1e-9)
    @Test fun rainNeedsTwentyPercentWiper() { assertTrue(ex(trip(cruise(40.0, 100, wiper = 25))).rain); assertFalse(ex(trip(cruise(40.0, 100, wiper = 10))).rain) }
    @Test fun chuseokIsOffDay() = assertTrue(KoreanHolidays.isOffDay(LocalDate.of(2026, 9, 25)))

    @Test fun headingAndRepeatedAnnouncementsDoNotDuplicateTurns() {
        val f = ex(trip(cruise(30.0, 30) + leftTurn(8), navi = listOf(
            TurnAhead(t(10), Signal.LEFT, 150.0), TurnAhead(t(20), Signal.LEFT, 80.0), TurnPassed(t(37), Signal.LEFT))))
        assertEquals(1, f.leftTurns)
        assertEquals(1, f.announcedTurns)
    }
    @Test fun signalDuringTurnCountsAsAnnounced() {
        val samples = (cruise(30.0, 30) + leftTurn(8)).mapIndexed { i, sample ->
            if (i >= 32) sample.copy(signal = Signal.LEFT) else sample
        }
        assertEquals(0, ex(trip(samples, listOf(TurnAhead(t(10), Signal.LEFT, 100.0)))).signalMisses)
    }
    @Test fun hazardBeforeTurnCountsAsSignal() {
        val samples = (cruise(30.0, 30) + leftTurn(8)).mapIndexed { i, sample ->
            if (i == 25) sample.copy(signal = Signal.HAZARD) else sample
        }
        assertEquals(0, ex(trip(samples, listOf(TurnAhead(t(10), Signal.LEFT, 100.0)))).signalMisses)
    }
    @Test fun staleOrDistantAnnouncementsDoNotMatch() {
        val samples = cruise(30.0, 80) + leftTurn(8)
        val f = ex(trip(samples, listOf(TurnAhead(t(0), Signal.LEFT, 100.0), TurnAhead(t(70), Signal.LEFT, 301.0))))
        assertEquals(1, f.leftTurns)
        assertEquals(0, f.announcedTurns)
    }
    @Test fun missingSteeringAndShortExposureRemainNull() {
        assertNull(ex(trip(cruise(50.0, 120).map { it.copy(steeringDeg = null) })).steeringStd)
        assertNull(ex(trip(cruise(50.0, 59))).steeringStd)
        assertNull(ex(trip(cruise(50.0, 59))).speedingRatio)
        val withoutHeading = ex(trip(cruise(50.0, 120, heading = null)))
        assertEquals(0.0, withoutHeading.steeringStd!!, 1e-9)
        assertEquals(0.0, withoutHeading.steeringReversalsPerMin!!, 1e-9)
        assertNull(ex(trip(cruise(50.0, 59, heading = null))).steeringStd)
    }
    @Test fun wobbleHasExpectedMagnitudeAndReversals() {
        val f = ex(trip(wobble(3.0)))
        assertEquals(3.0, f.steeringStd!!, 1e-9)
        assertEquals(48.0, f.steeringReversalsPerMin!!, 1e-9)
    }
    @Test fun rainDenominatorExcludesMissingWiperSamples() {
        val samples = cruise(40.0, 100).mapIndexed { i, s -> s.copy(wiperOn = when {
            i < 10 -> true
            i < 50 -> false
            else -> null
        }) }
        assertTrue(ex(trip(samples)).rain)
    }
    @Test fun guidedDistanceOnlyIncludesActiveGuidanceIntervals() {
        val f = ex(trip(cruise(36.0, 100), listOf(
            GuidanceStarted(t(20), LatLon(37.5, 127.0), "목적지"), Reroute(t(25)), GuidanceEnded(t(40)))))
        assertTrue(f.guided)
        assertEquals(0.2, f.guidedKm, 1e-9)
        assertEquals(1, f.reroutes)
    }
    @Test fun hardBrakesRequireBrakeAndDeduplicateThreeSeconds() {
        val samples = listOf(s(50.0), s(35.0, true), s(50.0), s(35.0, true),
            s(50.0), s(35.0, true), s(50.0), s(20.0, false))
        assertEquals(2, ex(trip(samples)).hardBrakes)
    }
    @Test fun hardBrakeUsesOneSecondDecelerationNotWholeGap() {
        val raw = trip(listOf(s(50.0), s(35.0, true)))
        assertEquals(0, ex(raw.copy(samples = listOf(raw.samples[0], raw.samples[1].copy(tMs = t(2))))).hardBrakes)
    }
    @Test fun timelineContainsMobilityAndNoVehicleMetrics() {
        val f = Features.fromPastTrip(t(0), t(600), LatLon(37.5, 127.0), LatLon(37.6, 127.0),
            10.0, LatLon(37.5, 127.0), setOf(Geo.cell(LatLon(37.6, 127.0))), KST)
        assertEquals(Source.TIMELINE, f.source)
        assertTrue(f.familiarDest)
        assertTrue(f.maxFromHomeKm!! > 11.0)
        assertEquals(0, f.hardBrakes)
        assertFalse(f.guided)
        assertNull(f.speedingRatio)
        assertNull(f.steeringStd)
    }
    @Test fun tripFeaturesSurviveJsonRoundTrip() {
        val f = ex(trip(cruise(50.0, 120))).copy(source = Source.SIM)
        assertEquals(f, Json.decodeFromString<TripFeatures>(Json.encodeToString(f)))
    }
    @Test fun lastKnownPositionAndAllPositionsDetermineMobility() {
        val home = LatLon(37.0, 127.0)
        val samples = listOf(s().copy(pos = home), s().copy(pos = LatLon(38.0, 127.0)),
            s().copy(pos = home), s().copy(pos = null))
        val f = Features.extract(trip(samples), home, setOf(Geo.cell(home)), KST)
        assertEquals(Geo.cell(home), f.endCell)
        assertTrue(f.maxFromHomeKm!! > 110.0)
        assertTrue(f.familiarDest)
    }
    @Test fun knownSubstitutesAndSpecialHolidaysAreIncluded() {
        listOf("2023-05-29", "2023-10-02", "2024-04-10", "2024-10-01", "2025-01-27", "2025-06-03",
            "2026-03-02", "2026-05-25", "2026-06-03", "2027-02-09", "2027-05-13", "2027-12-27")
            .forEach { assertTrue(KoreanHolidays.isOffDay(LocalDate.parse(it)), it) }
        assertFalse(KoreanHolidays.isOffDay(LocalDate.of(2026, 5, 12)))
    }
    @Test fun unavailableSignalSkipsAnnouncedTurnJudgment() {
        val samples = (cruise(30.0, 30) + leftTurn(8)).map { it.copy(signal = null) }
        val f = ex(trip(samples, listOf(TurnAhead(t(10), Signal.LEFT, 100.0))))
        assertEquals(1, f.leftTurns)
        assertEquals(0, f.announcedTurns)
        assertEquals(0, f.signalMisses)
    }
    @Test fun unavailableBrakeUsesDecelerationAndThreeSecondDebounce() {
        val samples = listOf(50.0, 35.0, 50.0, 35.0, 50.0, 35.0).map { s(it, brake = null) }
        assertEquals(2, ex(trip(samples)).hardBrakes)
    }
    @Test fun anyObservedBrakeValueKeepsBrakeOnRequirement() {
        val samples = listOf(s(50.0, false), s(35.0, null), s(50.0, null), s(35.0, false))
        assertEquals(0, ex(trip(samples)).hardBrakes)
    }
    @Test fun unavailableHeadingUsesSmallAbsoluteSteering() {
        val f = ex(trip(wobble(3.0).map { it.copy(headingDeg = null) }))
        assertEquals(3.0, f.steeringStd!!, 1e-9)
        assertEquals(48.0, f.steeringReversalsPerMin!!, 1e-9)
    }
    @Test fun partiallyMissingHeadingUsesFallbackForThatWindow() {
        val samples = wobble(3.0).mapIndexed { i, sample ->
            sample.copy(headingDeg = if (i % 10 == 0) null else i * 30.0 % 360)
        }
        assertEquals(3.0, ex(trip(samples)).steeringStd!!, 1e-9)
    }
    @Test fun headingFallbackRejectsLargeSteeringButFullHeadingKeepsOriginalRule() {
        assertNull(ex(trip(wobble(15.1).map { it.copy(headingDeg = null) })).steeringStd)
        assertEquals(15.0, ex(trip(wobble(15.0).map { it.copy(headingDeg = null) })).steeringStd!!, 1e-9)
        assertEquals(20.0, ex(trip(wobble(20.0))).steeringStd!!, 1e-9)
    }
}

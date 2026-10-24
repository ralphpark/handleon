package ai.handleon.car.collect

import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Snapshot
import ai.handleon.car.signals.Stamped
import ai.handleon.driveprint.*
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class LiveViewTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val noon = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val position = LatLon(37.4, 127.1)
    private fun sample(at: Long, speed: Double = 36.0) = Sample(at, speed, Gear.D, false,
        0.0, Signal.OFF, false, null, null, null)
    private fun trip(at: Long = noon) = RawTrip(listOf(sample(at), sample(at + 1_000)), emptyList(), emptyList())
    private fun view(now: Long = noon, snap: Snapshot = emptyMap(), events: LiveEvents = LiveEvents(),
                     trip: RawTrip? = null, previous: LiveView? = null) = LiveViewAssembler.from(
        true, snap, events, trip, now, previous, zone = zone)!!

    @Test fun disabledPresentationDoesNotExtractEvenAnInvalidTrip() {
        // This position has no sunrise on this date; extraction would throw if the gate were too late.
        assertNull(LiveViewAssembler.from(false, emptyMap(), LiveEvents(),
            trip().copy(samples = listOf(sample(noon).copy(pos = LatLon(90.0, 0.0)))), noon, zone = zone))
    }

    @Test fun recentTelltalesExpireAfterTenSecondsAndDoNotAccumulateDuplicates() {
        val events = LiveEvents().remember(emptyList(), listOf(TelltaleOn(noon - 10_001, "FCA"),
            TelltaleOn(noon - 10_000, "LKA"), TelltaleOn(noon - 1, "LKA")), noon)
        assertEquals(listOf("LKA"), view(events = events).telltales)
        assertTrue(view(now = noon + 10_000, events = events).telltales.isEmpty())
    }

    @Test fun latestTurnSurvivesTicksWithoutEvents() {
        val ahead = TurnAhead(noon, Signal.RIGHT, 80.0)
        val events = LiveEvents().remember(listOf(GuidanceStarted(noon - 2, position, "목적지"),
            TurnAhead(noon - 1, Signal.LEFT, 200.0), ahead), emptyList(), noon)
            .remember(emptyList(), emptyList(), noon + 1_000)
        val live = view(events = events)
        assertTrue(live.guiding)
        assertEquals(ahead, live.turnAhead)
    }

    @Test fun guidanceEndAndNewGuidanceDoNotShowThePreviousTurn() {
        val events = LiveEvents().remember(listOf(GuidanceStarted(noon - 2, position, "목적지"),
            TurnAhead(noon - 1, Signal.LEFT, 80.0), GuidanceEnded(noon)), emptyList(), noon)
        assertFalse(view(events = events).guiding)
        assertNull(view(events = events).turnAhead)
        val restarted = events.remember(listOf(GuidanceStarted(noon + 1, position, "다음 목적지")), emptyList(), noon + 1)
        assertTrue(view(events = restarted).guiding)
        assertNull(view(events = restarted).turnAhead)
    }

    @Test fun missingGearDoesNotHideOtherSignalsAndZeroLimitIsUnknown() {
        val live = view(snap = mapOf(Key.SPEED to Stamped(42.0, noon), Key.LIMIT to Stamped(0, noon),
            Key.STEERING to Stamped(-3.4, noon - 60_000)))
        assertEquals(42.0, live.speedKmh)
        assertEquals(-3.4, live.steeringDeg)
        assertNull(live.gear)
        assertNull(live.speedLimitKmh)
        assertEquals(noon, live.receivedAtMs)
    }

    @Test fun stalePeriodicSignalsExpireButOnChangeSignalsRemain() {
        val live = view(snap = mapOf(Key.SPEED to Stamped(42.0, noon - 3_001),
            Key.WIPER to Stamped(true, noon - 60_000), Key.GEAR to Stamped(Gear.D, noon - 60_000)))
        assertNull(live.speedKmh)
        assertTrue(live.wiperOn!!)
        assertEquals(Gear.D, live.gear)
        assertTrue(live.held)
    }

    @Test fun callbacksBetweenTickClockAndSnapshotAreNotDiscarded() {
        val received = noon + 1
        val events = LiveEvents().remember(emptyList(), listOf(TelltaleOn(received, "LKA")), noon)
        val live = view(snap = mapOf(Key.SPEED to Stamped(52.0, received)), events = events)
        assertEquals(52.0, live.speedKmh)
        assertEquals(listOf("LKA"), live.telltales)
        assertEquals(received, live.receivedAtMs)
    }

    @Test fun signalsUpdateEveryTickWhileFeaturesRefreshEveryTwoSeconds() {
        val first = view(trip = trip())
        val longer = trip().copy(samples = (0..3).map { sample(noon + it * 1_000) })
        val middle = view(noon + 1_000, mapOf(Key.SPEED to Stamped(52.0, noon + 1_000)), trip = longer, previous = first)
        assertEquals(52.0, middle.speedKmh)
        assertSame(first.trip, middle.trip)
        assertEquals(1, middle.history.size)
        val last = view(noon + 2_000, trip = longer, previous = middle)
        assertEquals(0.03, last.trip!!.distanceKm, 0.000001)
        assertEquals(2, last.history.size)
        assertEquals(noon, last.tripStartMs)
    }

    @Test fun tripEndAndNewTripNeverReusePreviousMetrics() {
        val first = view(trip = trip())
        val ended = view(noon + 1_000, previous = first)
        assertNull(ended.trip)
        assertTrue(ended.history.isEmpty())
        val next = view(noon + 1_000, trip = trip(noon + 1_000), previous = first)
        assertEquals(noon + 1_000, next.tripStartMs)
        assertEquals(1, next.history.size)
    }

    @Test fun featureHistoryIsBoundedToTenRealObservations() {
        var previous: LiveView? = null
        repeat(15) { index -> previous = view(noon + index * 2_000, trip = trip(), previous = previous) }
        assertEquals(10, previous!!.history.size)
    }

    @Test fun holdUsesCurrentWiperAndTimeInsteadOfWholeTripFlags() {
        val rainyTrip = trip().copy(samples = trip().samples.map { it.copy(wiperOn = true) })
        val dryNow = view(trip = rainyTrip, snap = mapOf(Key.WIPER to Stamped(false, noon)))
        assertTrue(dryNow.trip!!.rain)
        assertFalse(dryNow.held)
        assertTrue(view(snap = mapOf(Key.WIPER to Stamped(true, noon))).held)
    }

    @Test fun noPositionFallsBackToKoreanSixAmAndSixPmBoundaries() {
        for ((hour, held) in listOf(5 to true, 6 to false, 17 to false, 18 to true)) {
            val now = noon + (hour - 12) * 3_600_000L
            assertEquals(held, view(now).held, "hour=$hour")
        }
    }

    @Test fun positionUsesSunsetInsteadOfFixedSixPm() {
        val sunset = Sun.sunsetMs(position, java.time.LocalDate.of(2026, 10, 24))
        assertTrue(sunset + 1 < noon + 6 * 3_600_000L)
        assertTrue(view(sunset + 1, mapOf(Key.POS to Stamped(position, sunset))).held)
        assertFalse(view(sunset - 1, mapOf(Key.POS to Stamped(position, sunset - 1))).held)
    }
}

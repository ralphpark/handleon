package ai.handleon.car.collect

import ai.handleon.car.signals.*
import ai.handleon.car.store.*
import ai.handleon.driveprint.*
import org.junit.Test
import kotlin.test.*

class DriverFlowTest {
    private val obs = DriverObservation(SeatPose(1, 2, 3), null)
    @Test fun `missing seat and empty model is owner`() {
        assertEquals(DriverStatus.OWNER, DriverFlow.decide(DriverModel(), DriverObservation(null, null), false))
    }
    @Test fun `next-is-other flag wins`() {
        assertEquals(DriverStatus.OTHER, DriverFlow.decide(DriverModel(), obs, true))
    }
    @Test fun `uncertain call becomes pending not other`() {
        assertEquals(DriverStatus.PENDING, DriverFlow.decide(DriverModel(owner = listOf(obs)),
            obs.copy(seat = SeatPose(100, 2, 3)), false))
    }
    @Test fun `pending expires to other after 24 hours`() {
        assertTrue(DriverFlow.expire(listOf(10L), 10 + DriverFlow.ANSWER_WINDOW_MS - 1).isEmpty())
        assertEquals(listOf(10L), DriverFlow.expire(listOf(10L), 10 + DriverFlow.ANSWER_WINDOW_MS))
    }
    @Test fun `answer teaches the model`() {
        assertEquals(listOf(obs), DriverFlow.learnOnAnswer(DriverModel(), obs, true).owner)
        assertEquals(listOf(obs), DriverFlow.learnOnAnswer(DriverModel(), obs, false).others)
        assertEquals(DriverModel(), DriverFlow.learnOnAnswer(DriverModel(), null, true))
    }
    @Test fun `null seat reads do not crash collection`() {
        val signals = LatestSignals { 0 }
        signals.set(Key.SEAT_FORE_AFT, null)
        signals.set(Key.SEAT_HEIGHT, 3)
        assertNull(DriverFlow.seat(signals.snapshot()))
        val trained = DriverModel(owner = List(10) { obs.copy(style = StyleSample(30.0, 1.0, 1.0, 1.0, null)) })
        assertEquals(DriverStatus.OWNER, DriverFlow.decide(trained,
            DriverObservation(null, StyleSample(120.0, 20.0, 20.0, 9.0, null)), false))
    }
    @Test fun `seat is captured once until parked for thirty seconds`() {
        fun sample(ms: Long, gear: Gear) = Sample(ms, null, gear, null, null, null, null, null, null, null)
        val capture = DriverCapture()
        fun snapshot(n: Int) = mapOf(Key.SEAT_FORE_AFT to Stamped(n, 0), Key.SEAT_HEIGHT to Stamped(n, 0), Key.SEAT_BACKREST to Stamped(n, 0))
        capture.beforeSample(sample(0, Gear.D), snapshot(1), false)
        capture.beforeSample(sample(1000, Gear.D), snapshot(9), false)
        assertEquals(SeatPose(1, 1, 1), capture.seat)
        capture.afterSample(sample(2000, Gear.P))
        capture.afterSample(sample(32000, Gear.P))
        capture.beforeSample(sample(33000, Gear.D), snapshot(9), true)
        assertEquals(SeatPose(9, 9, 9), capture.seat)
        assertTrue(capture.other)
    }
    @Test fun `automatic owner is learned once after the correction window`() {
        val row = TripDriverEntity(10, "OWNER", kotlinx.serialization.json.Json.encodeToString(DriverObservation.serializer(), obs), null)
        val before = DriverFlow.settle(listOf(row), DriverModel(), 10 + DriverFlow.ANSWER_WINDOW_MS - 1)
        assertTrue(before.model.owner.isEmpty())
        val after = DriverFlow.settle(listOf(row), before.model, 10 + DriverFlow.ANSWER_WINDOW_MS)
        assertEquals(listOf(obs), after.model.owner)
        assertEquals(after, DriverFlow.settle(after.rows, after.model, 11 + DriverFlow.ANSWER_WINDOW_MS))
    }
}

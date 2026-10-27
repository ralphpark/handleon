package ai.handleon.driveprint

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.*

class DriverModelTest {
    private fun observation(offset: Int = 0) = DriverObservation(SeatPose(100 + offset, 50 + offset, 20 + offset),
        StyleSample(40.0 + offset, 3.0 + offset / 10.0, 4.0 + offset / 10.0, .5 + offset / 100.0, 1.0 + offset / 10.0))
    private fun trained() = (0..9).fold(DriverModel()) { model, i -> model.learn(observation(i % 3 - 1), true) }
    private fun sample(i: Int, speed: Double? = 50.0) = Sample(i * 1_000L, speed, Gear.D, false,
        if (i % 2 == 0) 3.0 else -3.0, Signal.OFF, false, null, 0.0, null)
    @Test fun `missing seat falls back to owner`() = assertEquals(DriverCall.OWNER, DriverModel().call(DriverObservation(null, null)))
    @Test fun `first trip without history is owner`() {
        assertEquals(DriverCall.OWNER, DriverModel().call(observation()))
        assertNull(DriverModel().ownerProbability(observation()))
    }
    @Test fun `seat rule asks when the seat moved a lot`() {
        val model = DriverModel(owner = List(3) { observation() })
        assertEquals(DriverCall.ASK, model.call(observation().copy(seat = SeatPose(140, 50, 20))))
        assertEquals(DriverCall.OWNER, model.call(observation().copy(seat = SeatPose(105, 50, 20))))
        assertEquals(DriverCall.OWNER, model.call(observation().copy(seat = SeatPose(115, 50, 20))))
        assertEquals(DriverCall.ASK, model.call(observation().copy(seat = SeatPose(100, 66, 20))))
        assertEquals(DriverCall.ASK, model.call(observation().copy(seat = SeatPose(100, 50, 36))))
    }
    @Test fun `model recognises owner after ten labels`() {
        val model = trained()
        assertEquals(DriverCall.OWNER, model.call(observation()))
        assertTrue(model.ownerProbability(observation())!! >= .8)
    }
    @Test fun `model rejects a clearly different driver`() {
        val model = (0..2).fold(trained()) { m, i -> m.learn(observation(40 + i), false) }
        assertEquals(DriverCall.OTHER, model.call(observation(41)))
        assertTrue(model.ownerProbability(observation(41))!! <= .3)
    }
    @Test fun `learn keeps at most fifty per class`() {
        val original = DriverModel()
        val model = (0..59).fold(original) { m, i -> m.learn(observation(i), true).learn(observation(-i), false) }
        assertEquals(50, model.owner.size)
        assertEquals(50, model.others.size)
        assertEquals(observation(10), model.owner.first())
        assertEquals(observation(-10), model.others.first())
        assertTrue(original.owner.isEmpty())
    }
    @Test fun `style needs five minutes of movement`() {
        assertNull(Style.firstMinutes(List(59) { sample(it) }))
        assertNotNull(Style.firstMinutes(List(60) { sample(it) }))
        assertNull(Style.firstMinutes(List(300) { sample(it, if (it < 59) 50.0 else 0.0) }))
    }
    @Test fun `model survives json round trip`() {
        val model = trained().learn(observation(40), false)
        val decoded = Json.decodeFromString<DriverModel>(Json.encodeToString(model))
        assertEquals(model, decoded)
        assertEquals(model.ownerProbability(observation()), decoded.ownerProbability(observation()))
    }
    @Test fun `missing observations and constant features remain finite`() {
        val empty = DriverModel(owner = List(10) { DriverObservation(null, null) })
        assertNull(empty.ownerProbability(DriverObservation(null, null)))
        assertEquals(DriverCall.OWNER, empty.call(DriverObservation(null, null)))
        val constant = DriverModel(owner = List(10) { observation() })
        assertTrue(constant.ownerProbability(observation())!!.isFinite())
        assertEquals(DriverCall.OWNER, constant.call(observation().copy(seat = null)))
        assertEquals(DriverCall.OTHER, constant.call(observation(500)))
    }
    @Test fun `ambiguous distributions ask with equal class priors`() {
        val model = DriverModel(owner = List(10) { observation() }, others = List(3) { observation() })
        assertEquals(.5, model.ownerProbability(observation())!!, 1e-9)
        assertEquals(DriverCall.ASK, model.call(observation()))
    }
    @Test fun `style uses only first window and reuses straight steering`() {
        val samples = List(360) { sample(it, if (it < 300) 50.0 else 150.0).copy(brake = it in 10..12 || it in 20..22) }
        val style = Style.firstMinutes(samples)!!
        assertEquals(50.0, style.meanSpeedKmh, 1e-9)
        assertEquals(0.0, style.accelP90)
        assertEquals(0.0, style.decelP90)
        assertEquals(.4, style.brakesPerMin, 1e-9)
        assertEquals(3.0, style.steeringStd!!, 1e-9)
        assertNull(Style.firstMinutes(List(60) { sample(300 + it) } + listOf(sample(0, 0.0))))
    }
    @Test fun `style computes acceleration percentiles and ignores invalid speeds`() {
        val samples = List(60) { sample(it, if (it % 2 == 0) 40.0 else 50.0) }
        val style = Style.firstMinutes(samples)!!
        assertEquals(45.0, style.meanSpeedKmh, 1e-9)
        assertEquals(10.0, style.accelP90, 1e-9)
        assertEquals(10.0, style.decelP90, 1e-9)
        assertNull(Style.firstMinutes(List(60) { sample(it, Double.NaN) }))
    }
}

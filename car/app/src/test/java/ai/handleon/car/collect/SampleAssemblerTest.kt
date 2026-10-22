package ai.handleon.car.collect

import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Stamped
import ai.handleon.driveprint.Gear
import ai.handleon.driveprint.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SampleAssemblerTest {
    private fun snap(vararg kv: Pair<Key, Stamped>) = mapOf(*kv)

    @Test fun noGearNoSample() = assertNull(SampleAssembler.toSample(10_000, snap(Key.SPEED to Stamped(40.0, 9_900))))

    @Test fun staleSpeedBecomesNull() {
        val s = SampleAssembler.toSample(10_000, snap(Key.GEAR to Stamped(Gear.D, 9_500), Key.SPEED to Stamped(40.0, 3_000)))!!
        assertNull(s.speedKmh)
    }

    @Test fun freshValuesPassThrough() {
        val s = SampleAssembler.toSample(10_000, snap(Key.GEAR to Stamped(Gear.D, 9_500), Key.SPEED to Stamped(40.0, 9_800)))!!
        assertEquals(40.0, s.speedKmh)
        assertEquals(Gear.D, s.gear)
    }

    // 조향·지시등·와이퍼·브레이크는 바뀔 때만 오므로 오래돼도 마지막 값이 맞다
    @Test fun eventDrivenValuesDoNotExpire() {
        val s = SampleAssembler.toSample(60_000, snap(Key.GEAR to Stamped(Gear.D, 0), Key.STEERING to Stamped(2.5, 0)))!!
        assertEquals(2.5, s.steeringDeg)
    }

    @Test fun stalePositionBecomesNull() {
        val s = SampleAssembler.toSample(10_000, snap(Key.GEAR to Stamped(Gear.D, 0), Key.POS to Stamped(LatLon(37.4, 127.1), 4_000)))!!
        assertNull(s.pos)
    }

    @Test fun timeIsOnWholeSeconds() = assertEquals(10_000L, SampleAssembler.toSample(10_437, snap(Key.GEAR to Stamped(Gear.P, 0)))!!.tMs)
}

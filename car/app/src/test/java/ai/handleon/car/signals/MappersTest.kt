package ai.handleon.car.signals

import ai.handleon.driveprint.Gear
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.Signal
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappersTest {
    @Test fun turnSignalMapsDirections() {
        assertEquals(Signal.LEFT, TurnSignalValue.LEFT.toSignal())
        assertEquals(Signal.RIGHT, TurnSignalValue.RIGHT.toSignal())
        assertEquals(Signal.OFF, TurnSignalValue.OFF.toSignal())
        assertNull((null as TurnSignalValue?).toSignal())
    }

    @Test fun keepLeftCountsAsLeft() = assertEquals(Signal.LEFT, TurnType.TURN_KEEP_L.toSignal())
    @Test fun uTurnCountsAsLeft() = assertEquals(Signal.LEFT, TurnType.TURN_UTURN.toSignal())
    @Test fun straightHasNoDirection() = assertNull(TurnType.TURN_STRAIGHT.toSignal())

    @Test fun wiperServiceAndOtherAreUnknown() {
        assertNull(WiperState.SERVICE.toWiperOn())
        assertNull(WiperState.OTHER.toWiperOn())
        assertEquals(true, WiperState.ON.toWiperOn())
        assertEquals(false, WiperState.OFF.toWiperOn())
    }

    @Test fun gearConstantsFromCarProperty() {
        assertEquals(Gear.P, gearFromCar(4))
        assertEquals(Gear.D, gearFromCar(8))
        assertEquals(Gear.R, gearFromCar(2))
        assertEquals(Gear.N, gearFromCar(1))
        assertEquals(Gear.UNKNOWN, gearFromCar(16))
    }

    @Test fun bearingPointsEastAndNorth() {
        assertTrue(kotlin.math.abs(bearingDeg(LatLon(37.0, 127.0), LatLon(37.0, 127.01)) - 90.0) < 0.5)
        assertTrue(bearingDeg(LatLon(37.0, 127.0), LatLon(37.01, 127.0)) < 0.5)
    }
}

package ai.handleon.car.signals

import ai.handleon.driveprint.Reroute
import ai.handleon.driveprint.TelltaleOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatestSignalsTest {
    @Test fun readyFlipsOnFirstVehicleValue() {
        val s = LatestSignals { 0L }
        assertFalse(s.ready.value)
        s.set(Key.POS, null)
        assertFalse(s.ready.value)
        s.set(Key.STEERING, 0.0)
        assertTrue(s.ready.value)
    }

    @Test fun snapshotKeepsReceiveTime() {
        var now = 1_000L
        val s = LatestSignals { now }
        s.set(Key.SPEED, 40.0)
        now = 3_000L
        val v = s.snapshot()[Key.SPEED]!!
        assertEquals(40.0, v.value)
        assertEquals(1_000L, v.atMs)
    }

    @Test fun eventsAreDrainedOnce() {
        val s = LatestSignals { 0L }
        s.onNavi(Reroute(1)); s.onNavi(Reroute(2)); s.onTelltale(TelltaleOn(3, "LKA"))
        val (navi, tell) = s.drainEvents()
        assertEquals(2, navi.size); assertEquals(1, tell.size)
        val (navi2, tell2) = s.drainEvents()
        assertTrue(navi2.isEmpty() && tell2.isEmpty())
    }
}

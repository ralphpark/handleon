package ai.handleon.car.ui

import ai.handleon.car.gleo.gleoMockVisible
import kotlin.test.*

class UxDrivingGateTest {
    @Test fun `initial connection and failed UX reads keep parked actions hidden`() {
        val gate = UxDrivingGate()
        assertTrue(gate.driving)
        val connection = gate.beginConnection()
        gate.report(connection, null)
        assertTrue(showDrivingScreen(gate.driving, true, Screen.Home))
        assertFalse(gleoMockVisible(true, gate.driving))
        gate.report(connection, false)
        assertFalse(gate.driving)
        gate.report(connection, true)
        assertTrue(gate.driving)
    }

    @Test fun `disconnect rejects stale parked callbacks until a fresh connection reports`() {
        val gate = UxDrivingGate()
        val old = gate.beginConnection()
        gate.report(old, false)
        gate.disconnect()
        assertTrue(gate.driving)
        gate.report(old, false)
        assertTrue(gate.driving)
        val current = gate.beginConnection()
        gate.report(old, false)
        assertTrue(gate.driving)
        gate.report(current, false)
        assertFalse(gate.driving)
    }
}

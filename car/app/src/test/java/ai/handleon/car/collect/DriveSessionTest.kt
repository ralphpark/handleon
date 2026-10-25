package ai.handleon.car.collect

import kotlin.test.*

class DriveSessionTest {
    @Test fun initiallyNull() {
        val session = DriveSession()
        assertNull(session.view)
        session.update(0, false); session.update(1000, false)
        assertNull(session.view)
    }
    @Test fun tenMinuteStopContinuesSession() {
        val session = DriveSession()
        session.update(1000, true); session.update(61_000, true)
        session.update(62_000, false); session.update(662_000, true)
        assertEquals(DriveSessionView(1000, 662_000), session.view)
        assertEquals(661_000, session.view!!.continuousMs)
    }
    @Test fun sixteenMinuteStopStartsNewSession() {
        val session = DriveSession()
        session.update(0, true); session.update(1000, false)
        session.update(961_000, true)
        assertEquals(DriveSessionView(961_000, 961_000), session.view)
    }
    @Test fun exactlyFifteenMinutesResetsAndStoppedTicksDoNotChangeView() {
        val session = DriveSession()
        session.update(0, true); session.update(1000, true)
        session.update(2000, false); session.update(901_999, false)
        assertEquals(DriveSessionView(0, 1000), session.view)
        session.update(902_000, true)
        assertEquals(DriveSessionView(902_000, 902_000), session.view)
    }
}

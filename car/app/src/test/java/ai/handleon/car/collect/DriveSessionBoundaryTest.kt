package ai.handleon.car.collect

import kotlin.test.*

class DriveSessionBoundaryTest {
    private val minute = 60_000L

    @Test fun stopOneMillisecondShortOfFifteenMinutesContinues() {
        val session = DriveSession()
        session.update(0, true); session.update(1000, false)
        session.update(1000 + 15 * minute - 1, true)
        assertEquals(DriveSessionView(0, 1000 + 15 * minute - 1), session.view)
    }

    @Test fun breakIsMeasuredFromFirstStoppedTickNotLastMovingTick() {
        // Sparse ticks: the last moving tick is at 0, the first stopped tick only at 5 minutes.
        val continued = DriveSession()
        continued.update(0, true); continued.update(5 * minute, false)
        continued.update(20 * minute - 1, true)
        assertEquals(0, continued.view!!.startMs)
        val restarted = DriveSession()
        restarted.update(0, true); restarted.update(5 * minute, false)
        restarted.update(20 * minute, true)
        assertEquals(DriveSessionView(20 * minute, 20 * minute), restarted.view)
    }

    @Test fun shortStopsDoNotAddUpToABreak() {
        val session = DriveSession()
        var now = 0L
        session.update(now, true)
        repeat(4) { // four 14-minute stops, almost an hour stopped in total
            session.update(now + 1000, false)
            now += 1000 + 14 * minute
            session.update(now, true)
        }
        assertEquals(DriveSessionView(0, now), session.view)
    }

    @Test fun stoppedTicksBeforeFirstMoveDoNotCountAsBreak() {
        val session = DriveSession()
        session.update(0, false); session.update(20 * minute, false)
        session.update(20 * minute + 1, true)
        session.update(40 * minute, true)
        assertEquals(DriveSessionView(20 * minute + 1, 40 * minute), session.view)
    }

    @Test fun customBreakLengthIsInclusive() {
        val session = DriveSession(breakMs = minute)
        session.update(0, true); session.update(1, false); session.update(minute, true)
        assertEquals(0, session.view!!.startMs)
        session.update(minute + 1, false); session.update(2 * minute + 1, true)
        assertEquals(DriveSessionView(2 * minute + 1, 2 * minute + 1), session.view)
    }
}

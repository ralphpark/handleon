package ai.handleon.driveprint

import ai.handleon.driveprint.Gear.*
import kotlin.test.*

class TripSegmenterTest {
    @Test fun snapshotRestoreContinuesTrip() {
        val all = drive(0, 120, 36.0, D)
        val events = listOf<NaviEvent>(GuidanceStarted(10_000, LatLon(37.5, 127.0), "집"),
            TurnAhead(20_000, Signal.LEFT, 100.0), TurnPassed(30_000, Signal.LEFT),
            Reroute(40_000), GuidanceEnded(90_000))
        events.forEach(seg::pushNavi)
        seg.pushTelltale(TelltaleOn(50_000, "LKA"))
        feed(all.take(60))
        val restored = TripSegmenter()
        restored.restore(seg.snapshot())
        assertTrue(all.drop(60).mapNotNull(restored::push).isEmpty())
        val trip = restored.flush()!!
        assertEquals(all, trip.samples)
        assertEquals(events, trip.navi)
        assertEquals(listOf(TelltaleOn(50_000, "LKA")), trip.telltales)
        assertNull(restored.flush())
    }
    @Test fun restoreAfterLongGapClosesPreviousTrip() {
        seg.pushNavi(Reroute(510_000))
        feed(drive(0, 120, 36.0, D))
        val restored = TripSegmenter()
        restored.restore(seg.snapshot())
        val closed = restored.push(drive(500_000, 1, 36.0, D).single())!!
        assertEquals(119_000L, closed.samples.last().tMs)
        drive(501_000, 120, 36.0, D).forEach(restored::push)
        assertEquals(listOf(Reroute(510_000)), restored.flush()!!.navi)
    }
    @Test fun snapshotRestoresContinuousParkingTimer() {
        feed(drive(0, 120, 36.0, D) + drive(120_000, 20, 0.0, P))
        val restored = TripSegmenter()
        restored.restore(seg.snapshot())
        val closed = drive(140_000, 11, 0.0, P).mapNotNull(restored::push)
        assertEquals(1, closed.size)
        assertEquals(150_000L, closed.single().samples.last().tMs)
    }
    private val seg = TripSegmenter()
    private fun drive(startMs: Long, seconds: Int, speedKmh: Double, gear: Gear) =
        List(seconds) { i -> Sample(startMs + i * 1000L, speedKmh, gear, false, null, null, null, null, null, null) }
    private fun feed(samples: List<Sample>) = samples.mapNotNull(seg::push)

    @Test fun parkDriveParkMakesOneTrip() {
        val trips = feed(drive(0, 10, 0.0, P) + drive(10_000, 600, 36.0, D) + drive(610_000, 40, 0.0, P))
        assertEquals(1, trips.size); assertEquals(6.0, trips[0].distanceKm(), 0.05)
    }
    @Test fun gapOver300SecondsSplitsTrip() {
        val trips = feed(drive(0, 300, 40.0, D) + drive(301_000 + 400_000, 300, 40.0, D)) + listOfNotNull(seg.flush())
        assertEquals(2, trips.size)
        assertEquals(299_000L, trips[0].samples.last().tMs)
        assertEquals(701_000L, trips[1].samples.first().tMs)
    }
    @Test fun parkingLotCreepIsDiscarded() = assertTrue(feed(drive(0, 50, 2.0, D) + drive(50_000, 40, 0.0, P)).isEmpty())
    @Test fun eventsInsideTripAreAttached() {
        seg.pushNavi(Reroute(-1))
        seg.pushNavi(Reroute(120_000))
        seg.pushNavi(Reroute(900_000))
        seg.pushTelltale(TelltaleOn(120_000, "LKA"))
        seg.pushTelltale(TelltaleOn(900_000, "FCA"))
        val trips = feed(drive(0, 300, 36.0, D) + drive(300_000, 40, 0.0, P))
        assertEquals(1, trips[0].navi.size)
        assertEquals(1, trips[0].telltales.size)
        assertEquals(Reroute(120_000), trips[0].navi.single())
        assertEquals("LKA", trips[0].telltales.single().type)
    }
    @Test fun exactly300SecondGapDoesNotSplit() {
        val trips = feed(drive(0, 100, 36.0, D) + drive(399_000, 100, 36.0, D)) + listOfNotNull(seg.flush())
        assertEquals(1, trips.size)
    }
    @Test fun parkingMustBeContinuousAndFlushOnlyReturnsOnce() {
        val trips = feed(drive(0, 120, 36.0, D) + drive(120_000, 20, 0.0, P) +
            drive(140_000, 120, 36.0, R) + drive(260_000, 30, 0.0, P))
        assertTrue(trips.isEmpty())
        assertNotNull(seg.flush())
        assertNull(seg.flush())
    }
    @Test fun distanceCapsEachGapAtTwoSeconds() {
        val samples = drive(0, 1, 36.0, D) + drive(10_000, 1, 36.0, D)
        assertEquals(0.02, RawTrip(samples, emptyList(), emptyList()).distanceKm(), 1e-12)
    }
    @Test fun futureEventsSurviveClosingThePreviousTrip() {
        seg.pushNavi(Reroute(800_000))
        feed(drive(0, 120, 36.0, D) + drive(120_000, 40, 0.0, P))
        feed(drive(700_000, 120, 36.0, D))
        assertEquals(listOf(Reroute(800_000)), seg.flush()!!.navi)
    }
}

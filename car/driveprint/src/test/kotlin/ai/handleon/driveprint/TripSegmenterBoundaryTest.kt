package ai.handleon.driveprint

import ai.handleon.driveprint.Gear.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class TripSegmenterBoundaryTest {
    private val seg = TripSegmenter()
    private fun sample(tMs: Long, speedKmh: Double, gear: Gear) = Sample(tMs, speedKmh, gear, false, null, null, null, null, null, null)
    private fun drive(startMs: Long, seconds: Int, speedKmh: Double, gear: Gear) =
        List(seconds) { i -> sample(startMs + i * 1000L, speedKmh, gear) }
    private fun feed(samples: List<Sample>, into: TripSegmenter = seg) = samples.mapNotNull(into::push)

    @Test fun parkingClosesAtExactlyThirtySecondsNotBefore() {
        assertTrue(feed(drive(0, 120, 36.0, D) + drive(120_000, 30, 0.0, P)).isEmpty())
        assertNull(seg.push(sample(149_999, 0.0, P)))
        val trip = seg.push(sample(150_000, 0.0, P))!!
        assertEquals(0L, trip.samples.first().tMs)
        assertEquals(150_000L, trip.samples.last().tMs)
        assertNull(seg.current())
    }

    @Test fun parkShorterThanThirtySecondsThenDriveKeepsOneTrip() {
        assertTrue(feed(drive(0, 120, 36.0, D) + sample(120_000, 0.0, P) + sample(149_999, 0.0, P) +
            drive(150_000, 60, 36.0, D)).isEmpty())
        val trip = seg.flush()!!
        assertEquals(0L, trip.samples.first().tMs)
        assertEquals(209_000L, trip.samples.last().tMs)
    }

    @Test fun longStopInDriveNeverClosesTrip() {
        val stopped = drive(60_000, 600, 0.0, D) // ten minutes at a standstill, gear still D
        assertTrue(feed(drive(0, 60, 36.0, D) + stopped + drive(660_000, 60, 36.0, D)).isEmpty())
        val trip = seg.flush()!!
        assertEquals(720, trip.samples.size)
        assertEquals(719_000L, trip.samples.last().tMs)
    }

    @Test fun neutralInterruptsParkingTimer() {
        assertTrue(feed(drive(0, 120, 36.0, D) + drive(120_000, 20, 0.0, P) + sample(140_000, 0.0, N) +
            drive(141_000, 30, 0.0, P)).isEmpty())
        val trip = seg.push(sample(171_000, 0.0, P))!!
        assertEquals(171_000L, trip.samples.last().tMs)
    }

    @Test fun tripStartsOnlyOnDriveOrReverse() {
        assertTrue(feed(drive(0, 5, 30.0, N) + drive(5_000, 5, 30.0, UNKNOWN) + drive(10_000, 5, 0.0, P)).isEmpty())
        assertNull(seg.current())
        assertNull(seg.flush())
        feed(drive(15_000, 70, 20.0, R))
        val trip = seg.flush()!!
        assertEquals(15_000L, trip.samples.first().tMs)
        assertEquals(R, trip.samples.first().gear)
    }

    @Test fun gapOneMillisecondOverFiveMinutesSplits() {
        feed(drive(0, 100, 36.0, D))
        val closed = seg.push(sample(99_000 + 300_001, 36.0, D))!!
        assertEquals(99_000L, closed.samples.last().tMs)
        assertEquals(listOf(399_001L), seg.current()!!.samples.map { it.tMs })
    }

    @Test fun gapFollowedByParkedSampleClosesWithoutStartingTrip() {
        feed(drive(0, 100, 36.0, D))
        val closed = seg.push(sample(500_000, 0.0, P))!!
        assertEquals(99_000L, closed.samples.last().tMs)
        assertNull(seg.current())
    }

    @Test fun gapAfterTooShortTripDiscardsItAndStartsFresh() {
        feed(drive(0, 30, 36.0, D))
        assertNull(seg.push(sample(400_000, 36.0, D)))
        assertEquals(listOf(400_000L), seg.current()!!.samples.map { it.tMs })
    }

    @Test fun gapUnderFiveMinutesStaysInTripAndAddsAtMostTwoSeconds() {
        assertTrue(feed(drive(0, 60, 36.0, D) + drive(259_000, 60, 36.0, D)).isEmpty())
        val trip = seg.flush()!!
        assertEquals(120, trip.samples.size)
        assertEquals(1.2, trip.distanceKm(), 1e-9) // 59 s + 2 s cap + 59 s at 36 km/h
    }

    @Test fun equalTimestampIsAcceptedButEarlierIsRejectedWithoutChangingState() {
        assertNull(seg.push(sample(1_000, 36.0, D)))
        assertNull(seg.push(sample(1_000, 36.0, D)))
        val before = seg.snapshot()
        assertFailsWith<IllegalArgumentException> { seg.push(sample(999, 36.0, D)) }
        assertEquals(before, seg.snapshot())
        assertEquals(2, seg.current()!!.samples.size)
    }

    @Test fun minimumDurationIsInclusiveAtSixtySeconds() {
        val base = drive(0, 60, 36.0, D)
        feed(base + sample(60_000, 36.0, D))
        assertNotNull(seg.flush())
        val shorter = TripSegmenter()
        feed(base + sample(59_999, 36.0, D), shorter)
        assertNull(shorter.flush())
    }

    @Test fun minimumDistanceIsThreeHundredMetres() {
        val kmPerSecond = 3600.0 / 256 // 1/256 km per one-second sample, exact in binary
        val enough = RawTrip(drive(0, 78, kmPerSecond, D), emptyList(), emptyList())
        assertEquals(77.0 / 256, enough.distanceKm())
        feed(enough.samples)
        assertNotNull(seg.flush())
        val short = TripSegmenter()
        feed(drive(0, 77, kmPerSecond, D), short) // 76/256 km, still 76 s long
        assertNull(short.flush())
    }

    @Test fun snapshotRestoreSnapshotIsStableMidParking() {
        seg.pushNavi(Reroute(30_000)); seg.pushNavi(Reroute(900_000))
        seg.pushTelltale(TelltaleOn(40_000, "LKA"))
        feed(drive(0, 120, 36.0, D) + drive(120_000, 10, 0.0, P))
        val text = seg.snapshot()
        val restored = TripSegmenter()
        feed(drive(0, 90, 50.0, D), restored) // existing state is replaced, not merged
        restored.restore(text)
        assertEquals(text, restored.snapshot())
        assertEquals(seg.current(), restored.current())
        assertEquals(feed(drive(130_000, 21, 0.0, P)), feed(drive(130_000, 21, 0.0, P), restored))
    }

    @Test fun emptySnapshotClearsTripInProgress() {
        feed(drive(0, 90, 36.0, D))
        seg.restore(TripSegmenter().snapshot())
        assertNull(seg.current())
        assertNull(seg.flush())
    }

    @Test fun invalidCheckpointIsRejectedAndKeepsCurrentState() {
        val parked = TripSegmenter()
        feed(drive(0, 120, 36.0, D) + drive(120_000, 10, 0.0, P), parked)
        val text = parked.snapshot()
        assertTrue("\"parkedSince\":120000" in text)
        feed(drive(0, 90, 36.0, D))
        val before = seg.snapshot()
        val s0 = Json.encodeToString(sample(0, 36.0, D))
        val s1 = Json.encodeToString(sample(1_000, 36.0, D))
        for ((bad, message) in listOf(
            text.replace("\"parkedSince\":120000", "\"parkedSince\":121000") to "Invalid parking checkpoint",
            text.replace("\"parkedSince\":120000", "\"parkedSince\":null") to "Invalid parking checkpoint",
            """{"samples":[],"navi":[],"telltales":[],"parkedSince":5}""" to "Invalid parking checkpoint",
            """{"samples":[$s1,$s0],"navi":[],"telltales":[],"parkedSince":null}""" to "Samples must be chronological",
        )) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { seg.restore(bad) }.message)
            assertEquals(before, seg.snapshot())
        }
    }

    @Test fun currentLeavesStateUntouched() {
        val twin = TripSegmenter()
        for (s in listOf(seg, twin)) {
            s.pushNavi(TurnAhead(20_000, Signal.LEFT, 80.0)); s.pushTelltale(TelltaleOn(30_000, "LKA"))
        }
        val samples = drive(0, 120, 36.0, D) + drive(120_000, 15, 0.0, P)
        feed(samples); feed(samples, twin)
        val before = seg.snapshot()
        repeat(3) { seg.current() }
        assertEquals(before, seg.snapshot())
        val rest = drive(135_000, 16, 0.0, P)
        assertEquals(feed(rest, twin), feed(rest))
    }

    @Test fun currentSkipsLengthChecksAndIncludesLaterEvents() {
        feed(drive(0, 10, 5.0, D))
        seg.pushNavi(Reroute(50_000)); seg.pushNavi(Reroute(5_000))
        val now = seg.current()!!
        assertEquals(10, now.samples.size)
        assertEquals(listOf(5_000L, 50_000L), now.navi.map { it.tMs }) // sorted, and after the last sample too
        assertNull(seg.flush()) // too short and too near to keep
        feed(drive(60_000, 1, 5.0, D))
        assertTrue(seg.current()!!.navi.isEmpty()) // events before the next trip's first sample are dropped
    }
}

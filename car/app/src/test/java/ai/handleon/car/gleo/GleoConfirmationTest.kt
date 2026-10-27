package ai.handleon.car.gleo

import ai.handleon.car.collect.DriverFlow
import ai.handleon.car.store.*
import ai.handleon.car.ui.PendingConfirm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GleoConfirmationTest {
    private val now = 1793070000000L
    private val latest = DriverCorrectionTrip(now - 3600000, now - 60000, DriverStatus.OWNER)

    @Test fun `repeat correction never falls back past the latest other driver trip`() = runTest {
        fun row(start: Long, source: String = "CAR") = TripEntity(startMs = start, endMs = start + 1000, source = source, json = "{}", importKey = null)
        val rows = listOf(row(now - 7200000), row(latest.startMs), row(now, "SIM"))
        assertEquals(DriverStatus.OWNER, latestCarForCorrection(rows, emptyMap())?.status)
        val current = latestCarForCorrection(rows, mapOf(latest.startMs to DriverStatus.OTHER))
        assertEquals(latest.startMs, current?.startMs)
        assertEquals(DriverStatus.OTHER, current?.status)
        val pending = MutableStateFlow<PendingConfirm?>(null)
        assertEquals(GleoAskResult.UNAVAILABLE, enqueueGleoConfirmation(PendingConfirm.MarkOtherDriver, pending, { current }, { now }))
        assertNull(pending.value)
    }

    @Test fun `queued correction pins the request trip and never overwrites a pending action`() = runTest {
        val pending = MutableStateFlow<PendingConfirm?>(null)
        assertEquals(GleoAskResult.QUEUED, enqueueGleoConfirmation(PendingConfirm.MarkOtherDriver, pending, { latest }, { now }))
        val request = assertIs<PendingConfirm.MarkOtherTrip>(pending.value)
        assertEquals(latest.startMs, request.startMs)
        assertEquals(now, request.requestedAtMs)
        assertEquals(GleoAskResult.BUSY, enqueueGleoConfirmation(PendingConfirm.PauseSharing, pending, { error("must not load") }, { now }))
        assertEquals(request, pending.value)
        assertTrue(driverCorrectionAllowed(request, latest, now, parked = true))
    }

    @Test fun `correction rejects expired future changed or already answered trips`() = runTest {
        val pending = MutableStateFlow<PendingConfirm?>(null)
        for (trip in listOf(null, latest.copy(startMs = now - DriverFlow.ANSWER_WINDOW_MS), latest.copy(endMs = now + 1))) {
            assertEquals(GleoAskResult.UNAVAILABLE, enqueueGleoConfirmation(PendingConfirm.MarkOtherDriver, pending, { trip }, { now }))
            assertNull(pending.value)
        }
        enqueueGleoConfirmation(PendingConfirm.MarkOtherDriver, pending, { latest }, { now })
        val request = assertIs<PendingConfirm.MarkOtherTrip>(pending.value)
        assertTrue(driverCorrectionAllowed(request, latest, latest.startMs + DriverFlow.ANSWER_WINDOW_MS - 1, true))
        assertFalse(driverCorrectionAllowed(request, latest, latest.startMs + DriverFlow.ANSWER_WINDOW_MS, true))
        assertFalse(driverCorrectionAllowed(request, latest, now - 1, true))
        assertFalse(driverCorrectionAllowed(request, latest.copy(startMs = now - 10000, endMs = now - 1000), now, true))
        assertFalse(driverCorrectionAllowed(request, latest.copy(status = DriverStatus.OTHER), now, true))
        assertFalse(driverCorrectionAllowed(request, latest, now, false))
        assertFalse(driverCorrectionAllowed(request, null, now, true))
    }
}

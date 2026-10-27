package ai.handleon.car.gleo

import ai.handleon.car.collect.DriverFlow
import ai.handleon.car.store.DriverCorrectionTrip
import ai.handleon.car.store.DriverStatus
import ai.handleon.car.ui.PendingConfirm
import kotlinx.coroutines.flow.MutableStateFlow

enum class GleoAskResult { QUEUED, BUSY, UNAVAILABLE }

private fun recent(latest: DriverCorrectionTrip?, nowMs: Long): Boolean = latest != null &&
    latest.status != DriverStatus.OTHER && latest.endMs >= latest.startMs && latest.endMs <= nowMs &&
    nowMs >= latest.startMs && nowMs - latest.startMs < DriverFlow.ANSWER_WINDOW_MS

suspend fun enqueueGleoConfirmation(action: PendingConfirm, pending: MutableStateFlow<PendingConfirm?>,
                                    latest: suspend () -> DriverCorrectionTrip?, nowMs: () -> Long,
                                    canQueue: () -> Boolean = { true }): GleoAskResult {
    if (!canQueue()) return GleoAskResult.UNAVAILABLE
    if (pending.value != null) return GleoAskResult.BUSY
    val bound = when (action) {
        PendingConfirm.MarkOtherDriver -> {
            val trip = latest()
            val at = nowMs()
            if (!recent(trip, at)) return GleoAskResult.UNAVAILABLE
            PendingConfirm.MarkOtherTrip(trip!!.startMs, at)
        }
        PendingConfirm.PauseSharing -> action
        is PendingConfirm.MarkOtherTrip -> return GleoAskResult.UNAVAILABLE
    }
    if (!canQueue()) return GleoAskResult.UNAVAILABLE
    return if (pending.compareAndSet(null, bound)) GleoAskResult.QUEUED else GleoAskResult.BUSY
}

fun driverCorrectionAllowed(request: PendingConfirm.MarkOtherTrip, latest: DriverCorrectionTrip?, nowMs: Long, parked: Boolean): Boolean =
    parked && nowMs >= request.requestedAtMs && recent(latest, nowMs) && latest?.startMs == request.startMs

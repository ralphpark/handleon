package ai.handleon.car.store

/** Metadata only. Keep OTHER in the candidate set so a repeat cannot fall back to an older owner trip. */
data class DriverCorrectionTrip(val startMs: Long, val endMs: Long, val status: DriverStatus)

fun latestCarForCorrection(rows: List<TripEntity>, statuses: Map<Long, DriverStatus>): DriverCorrectionTrip? =
    rows.filter { it.source == "CAR" }.maxWithOrNull(compareBy<TripEntity> { it.startMs }.thenBy { it.id })?.let {
        DriverCorrectionTrip(it.startMs, it.endMs, statuses[it.startMs] ?: DriverStatus.OWNER)
    }

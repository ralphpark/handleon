package ai.handleon.driveprint

import kotlinx.serialization.Serializable

@Serializable
data class Outing(
    val startMs: Long, val endMs: Long, val tripStarts: List<Long>,
    val driveMs: Long, val rested: Boolean, val longestContinuousMs: Long, val closed: Boolean,
) { val key: String get() = startMs.toString() }

object Outings {
    const val REST_MS = 15 * 60_000L
    const val END_MS = 30 * 60_000L

    /** Vehicle trips only; callers remove imported timeline trips before grouping. */
    fun group(trips: List<TripFeatures>, nowMs: Long): List<Outing> {
        val result = mutableListOf<Outing>()
        var current: Outing? = null
        var continuous = 0L
        for (trip in trips.sortedBy { it.startMs }) {
            val duration = (trip.endMs - trip.startMs).coerceAtLeast(0)
            val previous = current
            val gap = previous?.let { trip.startMs - it.endMs }
            if (previous == null || gap!! >= END_MS) {
                previous?.let(result::add)
                continuous = duration
                current = Outing(trip.startMs, trip.endMs, listOf(trip.startMs), duration,
                    false, duration, nowMs - trip.endMs >= END_MS)
            } else {
                val rest = gap >= REST_MS
                continuous = if (rest) duration else continuous + duration
                val end = maxOf(previous.endMs, trip.endMs)
                current = previous.copy(endMs = end, tripStarts = previous.tripStarts + trip.startMs,
                    driveMs = previous.driveMs + duration, rested = previous.rested || rest,
                    longestContinuousMs = maxOf(previous.longestContinuousMs, continuous),
                    closed = nowMs - end >= END_MS)
            }
        }
        current?.let(result::add)
        return result
    }
}

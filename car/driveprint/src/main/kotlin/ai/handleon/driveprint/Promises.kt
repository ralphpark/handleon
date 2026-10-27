package ai.handleon.driveprint

import kotlinx.serialization.Serializable

@Serializable enum class Promise { NO_NIGHT, REST_2H, NO_RAIN }
@Serializable data class PromiseResult(val promise: Promise, val kept: Int, val broken: Int)

object Promises {
    const val REST_LIMIT_MS = 2 * 60 * 60_000L

    fun evaluate(promises: Set<Promise>, trips: List<TripFeatures>, outings: List<Outing>): List<PromiseResult> =
        Promise.entries.filter { it in promises }.map { promise ->
            val broken = when (promise) {
                Promise.NO_NIGHT -> trips.map { it.night }
                Promise.NO_RAIN -> trips.map { it.rain }
                Promise.REST_2H -> outings.filter { it.closed }.map { it.longestContinuousMs > REST_LIMIT_MS }
            }
            PromiseResult(promise, broken.count { !it }, broken.count { it })
        }
}

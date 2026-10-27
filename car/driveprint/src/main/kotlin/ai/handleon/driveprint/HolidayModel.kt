package ai.handleon.driveprint

import java.time.LocalDate
import kotlin.math.hypot

/** Local linear model of weekly trip-count changes. No records leave this pure computation. */
object HolidayModel {
    const val SIZE = 15

    fun features(weekStart: LocalDate): DoubleArray {
        val x = DoubleArray(SIZE)
        x[0] = 1.0
        for (offset in 0L..6L) {
            when (KoreanHolidays.kind(weekStart.plusDays(offset))) {
                HolidayKind.LUNAR_NEW_YEAR -> x[1] = 1.0
                HolidayKind.CHUSEOK -> x[2] = 1.0
                HolidayKind.OTHER -> x[3] = 1.0
                null -> Unit
            }
        }
        // January is the reference month; unknown calendar years still have a known month.
        if (weekStart.monthValue >= 2) x[weekStart.monthValue + 2] = 1.0
        return x
    }

    fun predict(w: DoubleArray, weekStart: LocalDate): Double {
        validateWeights(w)
        return dot(w, features(weekStart))
    }

    /** [weeks] contains raw counts, not pre-normalized targets. An empty delta means no training. */
    fun localDelta(global: DoubleArray, weeks: List<Pair<LocalDate, Double>>,
                   lr: Double = 0.05, epochs: Int = 20, clip: Double = 1.0): DoubleArray {
        validateWeights(global)
        require(lr.isFinite() && lr >= 0 && epochs >= 0 && clip.isFinite() && clip >= 0)
        require(weeks.all { it.second.isFinite() && it.second >= 0 }) { "Trip counts must be finite and nonnegative" }
        require(weeks.map { it.first }.distinct().size == weeks.size) { "One count per week is required" }
        if (weeks.size < 4) return DoubleArray(0)
        val median = Stats.median(weeks.map { it.second })
        if (median == 0.0) return DoubleArray(0)
        val training = weeks.sortedBy { it.first }.map { (day, count) ->
            val target = count / median - 1.0
            require(target.isFinite()) { "Normalized trip counts must be finite" }
            features(day) to target
        }
        val local = global.copyOf()
        repeat(epochs) {
            // Chronological SGD on half squared error, identical order on each local round.
            for ((x, target) in training) {
                val error = dot(local, x) - target
                for (i in local.indices) local[i] -= lr * error * x[i]
                require(local.all(Double::isFinite)) { "Learning diverged" }
            }
        }
        val delta = DoubleArray(SIZE) { local[it] - global[it] }
        require(delta.all(Double::isFinite)) { "Model update must be finite" }
        val norm = delta.fold(0.0) { n, value -> hypot(n, value) }
        if (norm > clip) for (i in delta.indices) delta[i] *= clip / norm
        return delta
    }

    /** Sample-count FedAvg of updates; nonparticipants do not dilute the result. */
    fun average(deltas: List<Pair<DoubleArray, Int>>): DoubleArray {
        deltas.forEach { (delta, count) ->
            require(count >= 0) { "Sample count must be nonnegative" }
            if (delta.isNotEmpty()) validateWeights(delta)
        }
        val participating = deltas.filter { (delta, count) -> delta.isNotEmpty() && count > 0 }
        val total = participating.sumOf { it.second.toLong() }
        if (total == 0L) return DoubleArray(SIZE)
        return DoubleArray(SIZE) { i -> participating.sumOf { (delta, count) -> delta[i] * (count.toDouble() / total) } }
    }

    private fun validateWeights(w: DoubleArray) {
        require(w.size == SIZE && w.all(Double::isFinite)) { "Model needs 15 finite coefficients" }
    }

    private fun dot(w: DoubleArray, x: DoubleArray): Double = w.indices.sumOf { w[it] * x[it] }
}

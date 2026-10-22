package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.log2

object Periods {
    fun values(
        trips: List<TripFeatures>, from: LocalDate, toExclusive: LocalDate, zone: ZoneId,
        excludedDays: Set<LocalDate> = emptySet(), active: Boolean = false,
    ): Map<FeatureId, Double> {
        val calendarDays = ChronoUnit.DAYS.between(from, toExclusive)
        require(calendarDays > 0) { "Period must have a positive number of days" }
        val days = calendarDays - excludedDays.count { it >= from && it < toExclusive }
        if (days == 0L) return emptyMap()
        val dated = trips.map { it to Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .filter { (_, date) -> date >= from && date < toExclusive && date !in excludedDays }
        if (dated.isEmpty()) return if (active) mapOf(FeatureId.M1 to 0.0, FeatureId.M2 to 0.0, FeatureId.M3 to 0.0) else emptyMap()
        val selected = dated.map { it.first }
        val result = mutableMapOf<FeatureId, Double>()
        val monthlyScale = 30.0 / days
        result[FeatureId.M1] = selected.size * monthlyScale
        result[FeatureId.M2] = dated.map { it.second }.distinct().size.toDouble() / days
        result[FeatureId.M3] = selected.count { it.distanceKm >= 8 && it.distanceKm < 16 } * monthlyScale
        result[FeatureId.M4] = selected.count { it.night }.toDouble() / selected.size
        val places = selected.mapNotNull { it.endCell }.groupingBy { it }.eachCount()
        if (places.isNotEmpty()) {
            // Cardinality is the number of distinct destinations, not a repeated-event rate.
            result[FeatureId.M5] = places.size.toDouble()
            val visits = places.values.sum().toDouble()
            result[FeatureId.M6] = -places.values.sumOf {
                val p = it / visits
                p * log2(p)
            }
        }
        selected.mapNotNull { it.maxFromHomeKm }.takeIf { it.isNotEmpty() }?.let { result[FeatureId.M7] = Stats.percentile(it, 0.9) }

        val vehicle = selected.filter { it.source == Source.CAR || it.source == Source.SIM }
        val left = vehicle.sumOf { it.leftTurns.toLong() }
        val right = vehicle.sumOf { it.rightTurns.toLong() }
        if (left + right >= 10) result[FeatureId.W2] = left.toDouble() / (left + right)
        val familiar = vehicle.filter { it.familiarDest }
        val guidedKm = familiar.sumOf { it.guidedKm }
        if (guidedKm >= 5) result[FeatureId.W3] = familiar.sumOf { it.reroutes.toLong() } / guidedKm
        if (familiar.size >= 3) result[FeatureId.W4] = familiar.count { it.guided }.toDouble() / familiar.size

        val dry = vehicle.filter(::dayDry)
        medianOrNull(dry.mapNotNull { it.speedingRatio })?.let { result[FeatureId.O1] = it }
        medianOrNull(dry.mapNotNull { it.steeringStd })?.let { result[FeatureId.O7] = it }
        val distance = dry.sumOf { it.distanceKm }
        if (distance >= if (calendarDays == 1L) 10 else 20) {
            result[FeatureId.O2] = dry.sumOf { it.hardBrakes.toLong() } * 100.0 / distance
            result[FeatureId.O9] = dry.sumOf { it.lkaEvents.toLong() } * 100.0 / distance
        }
        val announced = dry.sumOf { it.announcedTurns.toLong() }
        if (announced >= 5) result[FeatureId.O8] = dry.sumOf { it.signalMisses.toLong() }.toDouble() / announced
        return result
    }

    fun dayDry(t: TripFeatures): Boolean = !t.night && !t.rain

    private fun medianOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

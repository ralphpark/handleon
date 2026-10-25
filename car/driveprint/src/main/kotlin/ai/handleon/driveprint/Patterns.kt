package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/** L1 aggregates only: no destinations, coordinates, cells or absolute distances. */
@Serializable
data class PatternMonth(
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val partial: Boolean,
    val trips: Int, val driveMinutes: Int, val driveDays: Int,
    val nightRatio: Double?, val longRatio: Double?,
    val radiusRatio: Double?, val placesRatio: Double?,
    val usualTrips: Int?, val usualMinutes: Int?, val usualNightRatio: Double?, val usualLongRatio: Double?,
)

object Patterns {
    const val LONG_TRIP_KM = 30.0

    fun monthly(trips: List<TripFeatures>, report: LongReport?, zone: ZoneId, today: LocalDate,
                months: Int = 6): List<PatternMonth> {
        if (trips.isEmpty() || months <= 0) return emptyList()
        val current = YearMonth.from(today)
        val grouped = trips.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) }
        val first = grouped.keys.minOrNull()!!
        fun monthTrips(month: YearMonth) = grouped[month].orEmpty()
        fun minutes(selected: List<TripFeatures>) = (selected.sumOf { it.endMs - it.startMs } / 60_000.0).roundToInt()
        fun ratio(selected: List<TripFeatures>, matches: (TripFeatures) -> Boolean): Double? =
            if (selected.isEmpty()) null else selected.count(matches).toDouble() / selected.size
        fun night(selected: List<TripFeatures>) = ratio(selected) { it.night }
        fun long(selected: List<TripFeatures>) = ratio(selected) { it.distanceKm >= LONG_TRIP_KM }
        val usual = report?.baselineWindows?.get(FeatureId.M1)?.let { window ->
            generateSequence(window.from) { it.plusMonths(1) }
                .takeWhile { it < window.toExclusive && it < current }.map(::monthTrips).toList()
        }.orEmpty()
        fun median(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.let(Stats::median)
        val usualTrips = median(usual.map { it.size.toDouble() })?.roundToInt()
        val usualMinutes = median(usual.map { minutes(it).toDouble() })?.roundToInt()
        val usualNight = median(usual.mapNotNull(::night))?.roundedRatio()
        val usualLong = median(usual.mapNotNull(::long))?.roundedRatio()
        return (0 until months).map { current.minusMonths(it.toLong()) }.takeWhile { it >= first }.map { month ->
            val selected = monthTrips(month)
            val values = Periods.values(selected, month.atDay(1), month.plusMonths(1).atDay(1), zone)
            fun againstBaseline(feature: FeatureId): Double? {
                val base = report?.baselines?.get(feature)?.median?.takeIf { it.isFinite() && it > 0 } ?: return null
                val value = values[feature]?.takeIf(Double::isFinite) ?: return null
                return (value / base).coerceAtMost(20.0).roundedRatio()
            }
            PatternMonth(month, month == current, selected.size, minutes(selected),
                selected.map { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }.distinct().size,
                night(selected)?.roundedRatio(), long(selected)?.roundedRatio(),
                againstBaseline(FeatureId.M7), againstBaseline(FeatureId.M5),
                usualTrips, usualMinutes, usualNight, usualLong)
        }
    }

    private fun Double.roundedRatio(): Double = (this * 1000).roundToLong() / 1000.0
}

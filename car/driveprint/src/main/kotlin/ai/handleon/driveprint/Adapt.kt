package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class AdaptWeek(
    @Serializable(with = LocalDateSerializer::class) val weekStart: LocalDate,
    val km: Double, val lka: Int, val fca: Int, val per100km: Double?,
)

object Adapt {
    /** Observed Monday-based weeks; a trip belongs to the week in which it started. */
    fun weekly(trips: List<TripFeatures>, zone: ZoneId): List<AdaptWeek> = trips.groupBy {
        val day = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
        day.minusDays(day.dayOfWeek.value - 1L)
    }.toSortedMap().map { (week, selected) ->
        val km = selected.sumOf { it.distanceKm }
        val lka = selected.sumOf { it.lkaEvents }
        val fca = selected.sumOf { it.fcaEvents }
        AdaptWeek(week, km, lka, fca, if (km < 20) null else (lka.toDouble() + fca) * 100 / km)
    }
}

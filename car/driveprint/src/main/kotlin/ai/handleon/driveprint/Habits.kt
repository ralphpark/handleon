package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class TripHabits(val startMs: Long, val minutes: Int, val signalKept: Int, val signalTotal: Int,
                      val hardBrakes: Int, val hardAccels: Int, val speedingRatio: Double?,
                      val night: Boolean, val rain: Boolean)
@Serializable
data class HabitSummary(
    @Serializable(with = LocalDateSerializer::class) val from: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val toExclusive: LocalDate,
    val trips: Int, val minutes: Int, val signalKept: Int, val signalTotal: Int,
    val hardBrakes: Int, val hardAccels: Int, val calmTrips: Int, val longStretches: Int,
    val nightTrips: Int, val speedingRatio: Double?, val story: String?,
)

object Habits {
    fun of(t: TripFeatures): TripHabits = TripHabits(t.startMs, (duration(t) / 60_000.0).roundToInt(),
        t.announcedTurns - t.signalMisses, t.announcedTurns, t.hardBrakes, t.hardAccels,
        t.speedingRatio, t.night, t.rain)

    fun summary(trips: List<TripFeatures>, outings: List<Outing>, from: LocalDate,
                toExclusive: LocalDate, zone: ZoneId): HabitSummary {
        require(from < toExclusive)
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun inside(ms: Long) = day(ms).let { it >= from && it < toExclusive }
        val selected = trips.filter { inside(it.startMs) }
        val known = selected.filter { it.speedingRatio != null && duration(it) > 0 }
        val knownMs = known.sumOf(::duration)
        val speeding = if (knownMs == 0L) null else known.sumOf { it.speedingRatio!! * duration(it) } / knownMs
        val longest = selected.sortedBy { it.startMs }.maxByOrNull(::duration)
        val weekdays = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
        return HabitSummary(from, toExclusive, selected.size, (selected.sumOf(::duration) / 60_000.0).roundToInt(),
            selected.sumOf { it.announcedTurns - it.signalMisses }, selected.sumOf { it.announcedTurns },
            selected.sumOf { it.hardBrakes }, selected.sumOf { it.hardAccels },
            selected.count { it.hardBrakes == 0 && it.hardAccels == 0 },
            outings.count { inside(it.startMs) && it.longestContinuousMs > 2 * 60 * 60_000L },
            selected.count { it.night }, speeding,
            longest?.let { weekdays[day(it.startMs).dayOfWeek.value - 1] + "에 가장 오래 운전하셨어요" })
    }

    fun line(h: TripHabits): String = buildList {
        val partial = h.signalTotal > 0 && h.signalKept < h.signalTotal
        if (h.signalTotal > 0) add(if (partial)
            "방향지시등 ${h.signalTotal}번 중 ${h.signalKept}번 켜셨어요"
            else "방향지시등 ${h.signalTotal}번 모두 켜셨어요")
        if (h.hardBrakes == 0) add("급제동 없음")
        else if (!partial) add("급제동 ${h.hardBrakes}번")
        add("${h.minutes}분")
    }.joinToString(" · ")

    private fun duration(t: TripFeatures) = (t.endMs - t.startMs).coerceAtLeast(0)
}

package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Familiar {
    private val zone = ZoneId.of("Asia/Seoul")

    fun from(trips: List<TripFeatures>, now: LocalDate): Set<Long> = trips.filter {
        val day = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
        day >= now.minusMonths(6) && day <= now
    }.mapNotNull { it.endCell }.groupingBy { it }.eachCount().filterValues { it >= 5 }.keys

    /** Preserve explicitly familiar destinations and learn only from earlier arrivals. */
    internal fun mark(trips: List<TripFeatures>): List<TripFeatures> {
        val history = ArrayDeque<Pair<LocalDate, Long>>()
        val counts = mutableMapOf<Long, Int>()
        return trips.sortedBy { it.startMs }.map { t ->
            val day = Instant.ofEpochMilli(t.startMs).atZone(zone).toLocalDate()
            while (history.isNotEmpty() && history.first().first < day.minusMonths(6)) {
                val cell = history.removeFirst().second
                counts[cell] = counts.getValue(cell) - 1
            }
            val learned = t.copy(familiarDest = t.familiarDest || (counts[t.endCell] ?: 0) >= 5)
            t.endCell?.let { history.addLast(day to it); counts[it] = (counts[it] ?: 0) + 1 }
            learned
        }
    }
}

package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class Driveprint(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    fun longReport(
        trips: List<TripFeatures>, labeledDays: Set<LocalDate>,
        resetMonths: Set<YearMonth>, upTo: YearMonth,
    ): LongReport {
        val dated = trips.map { it to date(it) }.filter { (_, day) ->
            day !in labeledDays && YearMonth.from(day) <= upTo
        }
        if (dated.isEmpty()) return LongTrack.evaluate(emptyList(), resetMonths)
        val grouped = dated.groupBy { YearMonth.from(it.second) }
        val first = grouped.keys.min()
        val inputs = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= upTo }.map { month ->
            val selected = grouped[month].orEmpty().map { it.first }
            MonthInput(month, Periods.values(selected, month.atDay(1), month.plusMonths(1).atDay(1), zone), selected.size)
        }.toList()
        val report = LongTrack.evaluate(inputs, resetMonths)
        if (report.stage != Stage.WATCH && report.stage != Stage.TALK) return report
        var from = report.months.firstOrNull()?.month?.atDay(1) ?: return report
        val end = upTo.plusMonths(1).atDay(1)
        val series = mutableListOf<Double>()
        val offsets = mutableListOf<Int>()
        var week = 0
        while (from < end) {
            val to = minOf(from.plusDays(7), end)
            val selected = dated.filter { (_, day) -> day >= from && day < to }.map { it.first }
            val values = Periods.values(selected, from, to, zone)
            val changes = values.mapNotNull { (f, value) ->
                report.baselines[f]?.let { f to Scoring.change(f, Stats.z(value, it)) }
            }.toMap()
            val indices = Domain.entries.mapNotNull { Scoring.domainIndex(changes, it) }
            if (indices.isNotEmpty()) {
                series.add(indices.average())
                offsets.add(week)
            }
            week++
            from = to
        }
        val change = Bocpd.lastChangeIndex(series)
        return report.copy(onsetWeeksAgo = change?.let { week - 1 - offsets[it] })
    }

    fun conditionReport(
        trips: List<TripFeatures>, labeledDays: Set<LocalDate>,
        resetDays: Set<LocalDate>, today: LocalDate,
    ): ConditionReport {
        val grouped = trips.filter { it.source == Source.CAR || it.source == Source.SIM }
            .groupBy(::date).filterKeys { it <= today }
        val inputs = grouped.map { (day, selected) ->
            DayInput(day, Periods.values(selected, day, day.plusDays(1), zone), selected.size, day in labeledDays)
        }
        return ShortTrack.evaluate(inputs, resetDays)
    }

    private fun date(trip: TripFeatures): LocalDate = Instant.ofEpochMilli(trip.startMs).atZone(zone).toLocalDate()
}

package ai.handleon.driveprint

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class Readiness(val weeklyWeeksLeft: Int, val monthlyFrom: YearMonth?, val monthlyTripsLeft: Int)

class Driveprint(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    fun longReport(
        trips: List<TripFeatures>, labeledDays: Set<LocalDate>,
        resets: Set<Reset>, today: LocalDate, activeMonths: Set<YearMonth>? = null,
    ): LongReport {
        val currentMonth = YearMonth.from(today)
        val upTo = currentMonth.minusMonths(1)
        val eligible = trips.map { it to date(it) }.filter { (_, day) ->
            day !in labeledDays && day <= today
        }
        val carDays = eligible.filter { it.first.source == Source.CAR }.map { it.second }.toSet()
        // ponytail: no cross-source conversion factor; timeline-only months can still differ in coverage.
        val dated = Familiar.mark(eligible.filterNot { it.first.source == Source.TIMELINE && it.second in carDays }
            .map { it.first }).map { it to date(it) }
        val progress = MonthProgress(currentMonth, dated.count { YearMonth.from(it.second) == currentMonth })
        val grouped = dated.groupBy { YearMonth.from(it.second) }
        val first = (grouped.keys + activeMonths.orEmpty()).filter { it <= upTo }.minOrNull()
            ?: return LongTrack.evaluate(emptyList(), resets).copy(inProgress = progress)
        val inputs = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= upTo }.map { month ->
            val selected = grouped[month].orEmpty().map { it.first }
            MonthInput(month, Periods.values(selected, month.atDay(1), month.plusMonths(1).atDay(1), zone,
                excludedDays = labeledDays, active = month in activeMonths.orEmpty()), selected.size)
        }.toList()
        val report = LongTrack.evaluate(inputs, resets).copy(inProgress = progress)
        if (report.stage != Stage.WATCH && report.stage != Stage.TALK) return report
        return report.copy(onsetWeeksAgo = onset(dated, report, upTo.plusMonths(1).atDay(1), labeledDays, resets))
    }

    private data class Week(val from: LocalDate, val to: LocalDate, val values: Map<FeatureId, Double>)

    internal fun onset(dated: List<Pair<TripFeatures, LocalDate>>, report: LongReport,
                       end: LocalDate, labels: Set<LocalDate>, resets: Set<Reset> = emptySet()): Int? {
        val current = report.months.lastOrNull() ?: return null
        val windows = report.baselineWindows.filter { (f, w) ->
            f.domain in current.over && w.toExclusive <= current.month &&
                !(f == FeatureId.M7 && resets.any {
                    val from = YearMonth.from(it.day)
                    it.kind == ResetKind.HOME_CHANGED && current.month >= from && current.month < from.plusMonths(6)
                })
        }
        if (windows.isEmpty()) return null
        val start = windows.values.minOf { it.from }.atDay(1)
        val weeks = generateSequence(start) { it.plusDays(7) }.takeWhile { it.plusDays(7) <= end }.map { from ->
            val to = from.plusDays(7)
            val selected = dated.filter { it.second >= from && it.second < to }.map { it.first }
            Week(from, to, Periods.values(selected, from, to, zone, excludedDays = labels))
        }.toList()
        fun inBaseline(week: Week, w: BaselineWindow) =
            week.from >= w.from.atDay(1) && week.to <= w.toExclusive.atDay(1)
        val weeklyBaselines = windows.mapNotNull { (f, w) ->
            Stats.baseline(weeks.filter { inBaseline(it, w) }.mapNotNull { it.values[f] }, f.minMad)
                ?.let { f to it }
        }.toMap()
        fun index(week: Week, calibration: Boolean): Double? {
            val changes = week.values.mapNotNull { (f, v) ->
                val b = weeklyBaselines[f] ?: return@mapNotNull null
                if (calibration && !inBaseline(week, windows.getValue(f))) return@mapNotNull null
                f to Scoring.change(f, Stats.z(v, b))
            }.toMap()
            return current.over.mapNotNull { Scoring.domainIndex(changes, it) }.takeIf { it.isNotEmpty() }?.average()
        }
        val prior = weeks.mapNotNull { index(it, true) }
        if (prior.isEmpty()) return null
        val baselineEnd = windows.values.maxOf { it.toExclusive }.atDay(1)
        val observed = weeks.filter { it.from >= baselineEnd }.mapNotNull { w -> index(w, false)?.let { w to it } }
        val change = Bocpd.lastChangeIndex(observed.map { it.second }, priorMean = prior.average()) ?: return null
        return java.time.temporal.ChronoUnit.WEEKS.between(observed[change].first.from, end).toInt()
    }

    fun conditionReport(
        trips: List<TripFeatures>, labeledDays: Set<LocalDate>,
        resets: Set<Reset>, today: LocalDate,
    ): ConditionReport {
        val grouped = trips.filter { it.source == Source.CAR || it.source == Source.SIM }
            .groupBy(::date).filterKeys { it <= today }
        val inputs = grouped.map { (day, selected) ->
            DayInput(day, Periods.values(selected, day, day.plusDays(1), zone), selected.size, day in labeledDays)
        }
        return ShortTrack.evaluate(inputs, resets, today)
    }

    fun weeklyReport(
        trips: List<TripFeatures>, labeledDays: Set<LocalDate>, resets: Set<Reset>,
        today: LocalDate, gate: ForecastGate? = null,
    ): WeeklyReport {
        val weeklyZone = ZoneId.of("Asia/Seoul")
        fun day(t: TripFeatures) = Instant.ofEpochMilli(t.startMs).atZone(weeklyZone).toLocalDate()
        fun monday(d: LocalDate) = d.minusDays(d.dayOfWeek.value - 1L)
        val currentWeek = monday(today)
        val selected = Familiar.mark(trips.filter {
            (it.source == Source.CAR || it.source == Source.SIM) && day(it) < currentWeek
        })
        val grouped = selected.groupBy { monday(day(it)) }
        val first = grouped.keys.minOrNull()
        val weeks = if (first == null) emptyList() else generateSequence(first) { it.plusWeeks(1) }
            .takeWhile { it < currentWeek }.map { from ->
                val weekTrips = grouped[from].orEmpty()
                WeekInput(from, Periods.values(weekTrips, from, from.plusWeeks(1), weeklyZone)
                    .filterKeys { it in WeeklyTrack.FEATURES }, weekTrips.size,
                    labeledDays.any { it >= from && it < from.plusWeeks(1) },
                    (0L..4L).any { KoreanHolidays.isOffDay(from.plusDays(it)) })
            }.toList()
        val applicable = resets.filter { it.day <= today }.toSet()
        val report = WeeklyTrack.evaluate(weeks, applicable, gate)
        return if (applicable.any { it.day >= currentWeek }) report.copy(
            status = WeeklyStatus.BUILDING, baselineFrom = null,
            weeksUntilReady = WeeklyTrack.MIN_BASE, top = emptyList()) else report
    }

    /** Convenience for history without labels or resets. */
    fun readiness(trips: List<TripFeatures>, weekly: WeeklyReport, today: LocalDate): Readiness =
        readiness(trips, weekly, today, emptySet(), emptySet())

    /** Pass the same labels and resets used for longReport so progress cannot revive excluded history. */
    fun readiness(trips: List<TripFeatures>, weekly: WeeklyReport, today: LocalDate,
                  labeledDays: Set<LocalDate>, resets: Set<Reset>): Readiness {
        if (longReport(trips, labeledDays, resets, today).stage != Stage.BUILDING)
            return Readiness(weekly.weeksUntilReady, null, 0)
        // LongTrack applies ALL resets at month boundaries; CAR/HOME resets retain mobility history.
        val resetMonth = resets.filter { it.kind == ResetKind.ALL && it.day <= today }
            .maxOfOrNull { YearMonth.from(it.day) }
        val vehicle = trips.filter {
            val day = date(it)
            (it.source == Source.CAR || it.source == Source.SIM) && day <= today && day !in labeledDays &&
                (resetMonth == null || YearMonth.from(day) >= resetMonth)
        }
        val first = vehicle.minOfOrNull { YearMonth.from(date(it)) }
        return Readiness(weekly.weeksUntilReady, first?.plusMonths(7), (50 - vehicle.size).coerceAtLeast(0))
    }

    private fun date(trip: TripFeatures): LocalDate = Instant.ofEpochMilli(trip.startMs).atZone(zone).toLocalDate()
}

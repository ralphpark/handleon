package ai.handleon.car.replay

import ai.handleon.driveprint.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ReplayFrame(val persona: ReplayPersona, val today: LocalDate, val throughMs: Long,
                       val trips: List<TripFeatures>, val longReport: LongReport, val weekly: WeeklyReport,
                       val condition: ConditionReport?, val firstDrive: Boolean, val tripLine: String?,
                       val habits: HabitSummary, val adapt: List<AdaptWeek>)

/** 저장소와 연결하지 않는 재생 전용 실행기. 각 구간의 출발일까지만 엔진에 전달한다. */
class ReplayController(trips: List<TripFeatures>, private val engine: Driveprint) {
    init { require(trips.all { it.source == Source.SIM }) { "모의 기록만 재생할 수 있어요" } }
    private val zone = ZoneId.of("Asia/Seoul")
    private val dated = trips.sortedBy { it.startMs }.map {
        it to Instant.ofEpochMilli(it.startMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
    }

    private data class Point(val today: LocalDate, val throughMs: Long, val firstDrive: Boolean = false)
    private fun points(persona: ReplayPersona): List<Point> {
        val first = dated.firstOrNull() ?: return emptyList()
        val last = dated.maxOf { Instant.ofEpochMilli(it.first.endMs).atZone(zone).toLocalDate() }
        fun boundary(day: LocalDate) = Point(day, day.atStartOfDay(zone).toInstant().toEpochMilli() - 1)
        return when (persona) {
            ReplayPersona.FATHER -> generateSequence(YearMonth.from(first.second)) { it.plusMonths(1) }
                .takeWhile { it <= YearMonth.from(last) }.map { boundary(it.plusMonths(1).atDay(1)) }.toList()
            ReplayPersona.CHILD -> dated.map { it.second }.distinct().map { day ->
                Point(day, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
            }
            else -> {
                val monday = first.second.minusDays(first.second.dayOfWeek.value - 1L)
                val weeks = generateSequence(monday) { it.plusWeeks(1) }.takeWhile { it <= last }
                    .map { boundary(it.plusWeeks(1)) }.toList()
                if (persona == ReplayPersona.NEW_CAR) listOf(Point(first.second, first.first.endMs, true)) + weeks else weeks
            }
        }
    }

    fun frameCount(persona: ReplayPersona) = points(persona).size

    /** Every engine input is a prefix of this persona only, never the real repository or future frames. */
    fun frames(persona: ReplayPersona, computation: CoroutineDispatcher = Dispatchers.Default): Flow<ReplayFrame> = flow {
        for (point in points(persona)) {
            delay(persona.stepMs)
            val frame = withContext(computation) {
                val selected = if (point.firstDrive) listOf(dated.first().first) else dated.map { it.first }
                    .filter { it.startMs <= point.throughMs && it.endMs <= point.throughMs }
                val latest = selected.maxByOrNull { it.startMs }
                val day = latest?.let { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() } ?: point.today
                val monday = day.minusDays(day.dayOfWeek.value - 1L)
                ReplayFrame(persona, point.today, point.throughMs, selected,
                    engine.longReport(selected, emptySet(), emptySet(), point.today),
                    engine.weeklyReport(selected, emptySet(), emptySet(), point.today),
                    if (persona == ReplayPersona.CHILD) engine.conditionReport(selected, emptySet(), emptySet(), point.today) else null,
                    point.firstDrive, latest?.let { Habits.line(Habits.of(it)) },
                    Habits.summary(selected, Outings.group(selected, point.throughMs), monday, monday.plusWeeks(1), zone),
                    if (persona == ReplayPersona.NEW_CAR) Adapt.weekly(selected, zone).takeLast(12) else emptyList())
            }
            emit(frame)
        }
    }

    fun monthly(): Flow<LongReport> = flow {
        val first = dated.firstOrNull()?.second?.let(YearMonth::from) ?: return@flow
        val last = YearMonth.from(dated.last().second)
        var month = first
        while (month <= last) {
            delay(2_500)
            val selected = dated.takeWhile { it.second < month.plusMonths(1).atDay(1) }.map { it.first }
            emit(engine.longReport(selected, emptySet(), emptySet(), month.plusMonths(1).atDay(1)))
            month = month.plusMonths(1)
        }
    }

    fun daily(): Flow<ConditionReport> = flow {
        for (day in dated.map { it.second }.distinct()) {
            delay(500)
            val selected = dated.takeWhile { it.second <= day }.map { it.first }
            emit(engine.conditionReport(selected, emptySet(), emptySet(), day))
        }
    }
}

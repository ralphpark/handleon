package ai.handleon.car.replay

import ai.handleon.driveprint.ConditionReport
import ai.handleon.driveprint.Driveprint
import ai.handleon.driveprint.LongReport
import ai.handleon.driveprint.TripFeatures
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 저장소와 연결하지 않는 재생 전용 실행기. 각 구간의 출발일까지만 엔진에 전달한다. */
class ReplayController(trips: List<TripFeatures>, private val engine: Driveprint) {
    private val dated = trips.sortedBy { it.startMs }.map {
        it to Instant.ofEpochMilli(it.startMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
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

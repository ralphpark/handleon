package ai.handleon.car.collect

import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Snapshot
import ai.handleon.car.store.DriverStatus
import ai.handleon.car.store.TripDriverEntity
import ai.handleon.driveprint.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DriverFlow {
    const val ANSWER_WINDOW_MS = 24 * 60 * 60_000L
    fun decide(model: DriverModel, obs: DriverObservation, nextIsOther: Boolean): DriverStatus = when {
        nextIsOther -> DriverStatus.OTHER
        obs.seat == null -> DriverStatus.OWNER // 시트 미지원 차량은 수동 확인만 사용한다.
        model.call(obs) == DriverCall.OWNER -> DriverStatus.OWNER
        else -> DriverStatus.PENDING
    }
    fun expire(pendingStartMs: List<Long>, nowMs: Long): List<Long> =
        pendingStartMs.filter { nowMs >= it && nowMs - it >= ANSWER_WINDOW_MS }
    fun learnOnAnswer(model: DriverModel, obs: DriverObservation?, isOwner: Boolean): DriverModel =
        obs?.let { model.learn(it, isOwner) } ?: model
    fun seat(snapshot: Snapshot): SeatPose? {
        val fore = snapshot[Key.SEAT_FORE_AFT]?.value as? Int ?: return null
        val height = snapshot[Key.SEAT_HEIGHT]?.value as? Int ?: return null
        val back = snapshot[Key.SEAT_BACKREST]?.value as? Int ?: return null
        return SeatPose(fore, height, back)
    }
    data class Settlement(val rows: List<TripDriverEntity>, val model: DriverModel)
    fun settle(rows: List<TripDriverEntity>, model: DriverModel, nowMs: Long): Settlement {
        var learned = model
        val due = expire(rows.filter { it.decidedMs == null }.map { it.startMs }, nowMs).toSet()
        return Settlement(rows.map { row ->
            if (row.startMs !in due) row else when (DriverStatus.valueOf(row.status)) {
                DriverStatus.PENDING -> row.copy(status = "OTHER", decidedMs = nowMs)
                DriverStatus.OWNER -> {
                    learned = learnOnAnswer(learned, row.observation(), true)
                    row.copy(decidedMs = nowMs)
                }
                DriverStatus.OTHER -> row
            }
        }, learned)
    }
}

fun TripDriverEntity.observation(): DriverObservation? = observationJson?.let { Json.decodeFromString<DriverObservation>(it) }

/** Frozen at the first non-P sample, even if that read is null. Persisted with the segmenter. */
@Serializable
class DriverCapture(var captured: Boolean = false, var seat: SeatPose? = null, var other: Boolean = false,
                    private var parkedSince: Long? = null) {
    fun beforeSample(sample: Sample, snapshot: Snapshot, nextIsOther: Boolean): Boolean {
        if (captured || sample.gear == Gear.P) return false
        captured = true; seat = DriverFlow.seat(snapshot); other = nextIsOther
        return true
    }
    fun afterSample(sample: Sample) {
        if (sample.gear != Gear.P) parkedSince = null
        else {
            val since = parkedSince ?: sample.tMs.also { parkedSince = it }
            if (sample.tMs - since >= 30_000) clear()
        }
    }
    fun clear() { captured = false; seat = null; other = false; parkedSince = null }
}

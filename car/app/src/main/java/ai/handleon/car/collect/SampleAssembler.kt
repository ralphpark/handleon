package ai.handleon.car.collect

import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Snapshot
import ai.handleon.driveprint.Gear
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.Sample
import ai.handleon.driveprint.Signal

/** 최신값 묶음을 엔진의 1초 샘플로. 기어가 아직 없으면(차량 신호 연결 전) 샘플을 만들지 않는다. */
object SampleAssembler {
    // 주기적으로 오는 값만 나이를 본다. 조향·지시등·와이퍼·브레이크·제한속도는 바뀔 때만 오므로 마지막 값을 그대로 쓴다.
    private val maxAgeMs = mapOf(Key.SPEED to 3_000L, Key.POS to 5_000L, Key.HEADING to 5_000L)

    fun toSample(nowMs: Long, snap: Snapshot): Sample? {
        val gear = snap[Key.GEAR]?.value as? Gear ?: return null
        fun value(key: Key): Any? {
            val s = snap[key] ?: return null
            val limit = maxAgeMs[key] ?: return s.value
            return s.value.takeIf { nowMs - s.atMs <= limit }
        }
        return Sample(
            tMs = nowMs / 1000 * 1000, // 엔진의 직진 창은 1초 간격을 본다(리뷰 표 "1Hz 샘플 간격")
            speedKmh = value(Key.SPEED) as? Double,
            gear = gear,
            brake = value(Key.BRAKE) as? Boolean,
            steeringDeg = value(Key.STEERING) as? Double,
            signal = value(Key.SIGNAL) as? Signal,
            wiperOn = value(Key.WIPER) as? Boolean,
            pos = value(Key.POS) as? LatLon,
            headingDeg = value(Key.HEADING) as? Double,
            speedLimitKmh = value(Key.LIMIT) as? Int,
        )
    }
}

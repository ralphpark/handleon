package ai.handleon.car.collect

import ai.handleon.car.signals.Key
import ai.handleon.car.signals.Snapshot
import ai.handleon.driveprint.*
import java.time.Instant
import java.time.ZoneId

/** 발표 화면 전용 읽기 사본. receivedAtMs는 실제 마지막 수신 시각, updatedAtMs는 틱 시각이다. */
data class LiveView(
    val speedKmh: Double?,
    val speedLimitKmh: Int?,
    val gear: Gear?,
    val steeringDeg: Double?,
    val signal: Signal?,
    val wiperOn: Boolean?,
    val brake: Boolean?,
    val telltales: List<String>,
    val guiding: Boolean,
    val turnAhead: TurnAhead?,
    val receivedAtMs: Long?,
    val updatedAtMs: Long,
    val trip: TripFeatures?,
    val tripUpdatedAtMs: Long?,
    val held: Boolean,
    val history: List<TripFeatures> = emptyList(),
) {
    val tripStartMs: Long? get() = trip?.startMs
}

/** drain한 이벤트를 다시 소비하지 않고 기억한다. 수집 틱 하나만 이 사본을 교체한다. */
data class LiveEvents(
    val guiding: Boolean = false,
    val turnAhead: TurnAhead? = null,
    val telltales: List<TelltaleOn> = emptyList(),
    val receivedAtMs: Long? = null,
) {
    fun remember(navi: List<NaviEvent>, warnings: List<TelltaleOn>, nowMs: Long): LiveEvents {
        var next = this
        for (event in navi.sortedBy { it.tMs }) {
            next = when (event) {
                is GuidanceStarted -> next.copy(guiding = true, turnAhead = null)
                is GuidanceEnded -> next.copy(guiding = false, turnAhead = null)
                is TurnAhead -> next.copy(turnAhead = event)
                else -> next // TurnPassed·Reroute의 엔진 처리는 기존 수집 경로에 맡긴다.
            }
        }
        return next.copy(
            telltales = (telltales + warnings).filter { nowMs - it.tMs <= 10_000 }
                .groupBy { it.type }.values.map { same -> same.maxBy { it.tMs } },
            receivedAtMs = listOfNotNull(receivedAtMs, navi.maxOfOrNull { it.tMs }, warnings.maxOfOrNull { it.tMs }).maxOrNull(),
        )
    }
}

/** Android·시계·저장소에 접근하지 않는 계산. 이전 사본을 받아 지표만 2초 간격으로 갱신한다. */
object LiveViewAssembler {
    fun needsTripUpdate(trip: RawTrip?, previous: LiveView?, nowMs: Long): Boolean {
        val start = trip?.samples?.firstOrNull()?.tMs ?: return false
        val last = previous?.tripUpdatedAtMs ?: return true
        return previous.tripStartMs != start || nowMs - last !in 0 until 2_000
    }

    fun from(
        presentation: Boolean,
        snap: Snapshot,
        events: LiveEvents,
        currentTrip: RawTrip?,
        nowMs: Long,
        previous: LiveView? = null,
        home: LatLon? = null,
        familiar: Set<Long> = emptySet(),
        zone: ZoneId = ZoneId.of("Asia/Seoul"),
    ): LiveView? {
        if (!presentation) return null
        fun value(key: Key): Any? {
            val stamped = snap[key] ?: return null
            // SampleAssembler처럼 변경 때만 오는 신호는 시간만으로 비우지 않는다.
            val maxAge = when (key) { Key.SPEED -> 3_000L; Key.POS -> 5_000L; else -> null }
            // 틱 시각을 읽은 직후, snapshot 전에 도착한 콜백도 최신값이다.
            return stamped.value.takeIf { maxAge == null || nowMs - stamped.atMs <= maxAge }
        }
        val position = value(Key.POS) as? LatLon
        val wiper = value(Key.WIPER) as? Boolean
        val fallbackNight = Instant.ofEpochMilli(nowMs).atZone(zone).hour.let { it >= 18 || it < 6 }
        val night = position?.let { runCatching { Sun.isNight(it, nowMs, zone) }.getOrDefault(fallbackNight) } ?: fallbackNight
        val refresh = needsTripUpdate(currentTrip, previous, nowMs)
        val features = when {
            currentTrip == null -> null
            refresh -> Features.extract(currentTrip, home, familiar, zone)
            else -> previous?.trip
        }
        val sameTrip = features != null && features.startMs == previous?.tripStartMs
        val history = when {
            features == null -> emptyList()
            refresh -> ((if (sameTrip) previous!!.history else emptyList()) + features).takeLast(10)
            else -> previous?.history.orEmpty()
        }
        return LiveView(
            speedKmh = (value(Key.SPEED) as? Double)?.takeIf { it.isFinite() && it >= 0 },
            speedLimitKmh = (value(Key.LIMIT) as? Int)?.takeIf { it > 0 },
            gear = value(Key.GEAR) as? Gear,
            steeringDeg = (value(Key.STEERING) as? Double)?.takeIf { it.isFinite() },
            signal = value(Key.SIGNAL) as? Signal,
            wiperOn = wiper,
            brake = value(Key.BRAKE) as? Boolean,
            telltales = events.telltales.filter { nowMs - it.tMs <= 10_000 }.map { it.type }.distinct(),
            guiding = events.guiding,
            turnAhead = events.turnAhead,
            receivedAtMs = listOfNotNull(snap.values.maxOfOrNull { it.atMs }, events.receivedAtMs).maxOrNull(),
            updatedAtMs = nowMs,
            trip = features,
            tripUpdatedAtMs = if (features == null) null else if (refresh) nowMs else previous?.tripUpdatedAtMs,
            held = night || wiper == true,
            history = history,
        )
    }
}

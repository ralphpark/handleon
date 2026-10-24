package ai.handleon.car.ui

import ai.handleon.car.collect.LiveView
import ai.handleon.driveprint.Gear
import ai.handleon.driveprint.Signal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class DevEngineUi(
    val waitingText: String?,
    val speedText: String,
    val speedLimitText: String,
    val gearText: String,
    val steeringText: String,
    val signalText: String,
    val wiperText: String,
    val brakeText: String,
    val telltalesText: String,
    val guidanceText: String,
    val turnDirection: Signal?,
    val distanceText: String,
    val hardBrakesText: String,
    val steeringStdText: String,
    val announcedTurnsText: String,
    val signalMissesText: String,
    val tripHint: String?,
    val steeringHint: String?,
    val held: Boolean,
    val judgmentText: String,
    val distanceHistory: List<Double?>,
    val hardBrakesHistory: List<Double?>,
    val steeringHistory: List<Double?>,
    val turnsHistory: List<Double?>,
    val missesHistory: List<Double?>,
    val hasSignalMisses: Boolean,
) {
    companion object {
        fun from(live: LiveView?): DevEngineUi {
            val waiting = "수집 서비스를 기다리고 있어요".takeIf { live == null }
            val trip = live?.trip
            val ahead = live?.turnAhead?.takeIf { live.guiding }
            val direction = when (ahead?.direction) { Signal.LEFT -> "좌회전"; Signal.RIGHT -> "우회전"; else -> null }
            val steering = live?.steeringDeg?.takeIf(Double::isFinite)?.roundToInt()
            val history = live?.history.orEmpty()
            return DevEngineUi(
                waitingText = waiting,
                speedText = live?.speedKmh?.takeIf { it.isFinite() && it >= 0 }?.roundToInt()?.toString() ?: "—",
                speedLimitText = live?.speedLimitKmh?.takeIf { it > 0 }?.toString() ?: "—",
                gearText = live?.gear?.takeUnless { it == Gear.UNKNOWN }?.name ?: "—",
                steeringText = steering?.let { (if (it < 0) "−" else if (it > 0) "+" else "") + abs(it.toLong()) + "°" } ?: "—",
                signalText = when (live?.signal) { Signal.LEFT -> "왼쪽"; Signal.RIGHT -> "오른쪽"; Signal.HAZARD -> "비상"; Signal.OFF -> "꺼짐"; null -> "—" },
                wiperText = when (live?.wiperOn) { true -> "켜짐"; false -> "꺼짐"; null -> "—" },
                brakeText = when (live?.brake) { true -> "밟음"; false -> "놓음"; null -> "—" },
                telltalesText = if (live == null) "—" else live.telltales.distinct().joinToString(" · ") {
                    when (it) { "LKA" -> "차로 유지 보조"; "FCA" -> "전방 충돌 방지"; else -> it }
                }.ifEmpty { "없음" },
                guidanceText = when {
                    live == null -> "—"
                    !live.guiding -> "안내 중 아님"
                    direction == null -> "안내 중 · 다음 회전을 기다려요"
                    else -> "$direction · ${ahead!!.distanceM.takeIf { it.isFinite() && it >= 0 }?.roundToInt()?.let { "${it}m" } ?: "—"}"
                },
                turnDirection = ahead?.direction?.takeIf { direction != null },
                distanceText = trip?.distanceKm.decimal(),
                hardBrakesText = trip?.hardBrakes?.toString() ?: "—",
                steeringStdText = trip?.steeringStd.decimal(),
                announcedTurnsText = trip?.announcedTurns?.toString() ?: "—",
                signalMissesText = trip?.signalMisses?.toString() ?: "—",
                tripHint = "기어를 D 로 두면 트립이 시작돼요".takeIf { trip == null },
                steeringHint = "시속 40km 이상 곧은 길에서 재요".takeIf { trip != null && trip.steeringStd?.isFinite() != true },
                held = live?.held == true,
                judgmentText = waiting ?: if (live?.held == true) "밤이거나 와이퍼가 켜져 판단을 쉬어요" else "낮 · 맑음 → 조작 지표를 판단해요",
                distanceHistory = history.map { it.distanceKm },
                hardBrakesHistory = history.map { it.hardBrakes.toDouble() },
                steeringHistory = history.map { it.steeringStd },
                turnsHistory = history.map { it.announcedTurns.toDouble() },
                missesHistory = history.map { it.signalMisses.toDouble() },
                hasSignalMisses = (trip?.signalMisses ?: 0) > 0,
            )
        }

        private fun Double?.decimal(): String = this?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    }
}

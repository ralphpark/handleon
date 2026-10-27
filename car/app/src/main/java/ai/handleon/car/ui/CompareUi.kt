package ai.handleon.car.ui

import ai.handleon.car.warmstart.BlueLinkMonth
import ai.handleon.driveprint.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

fun lostWayCard(latest: TripFeatures?, home: LatLon?, nowMs: Long, dismissedStartMs: Long?): Long? {
    if (latest == null || home == null || latest.source != Source.CAR || latest.startMs == dismissedStartMs) return null
    if (latest.endMs > nowMs || nowMs - latest.endMs !in 0 until 30 * 60_000L) return null
    return latest.startMs.takeIf { LostWay.flagged(latest) }
}

fun blueLinkCompare(previous: List<BlueLinkMonth>, currentMonthMinutes: Int?): String? {
    if (currentMonthMinutes == null || currentMonthMinutes < 0) return null
    val months = previous.filter { it.drivingMinutes >= 0 }.distinctBy { it.month }.sortedBy { it.month }.takeLast(3)
    if (months.size < 3) return null
    val total = months.sumOf { it.drivingMinutes.toLong() }
    if (total == 0L) return null
    val difference = currentMonthMinutes.toLong() * 3 - total
    // Integer comparison keeps exactly +/-20 percent inside the similar band.
    if (abs(difference) * 5 <= total) return "이전 차와 비슷하게 운전하세요 · 모의"
    val percent = (abs(difference).toDouble() * 100 / total).roundToLong()
    return "이전 차보다 운전 시간이 ${percent}% ${if (difference < 0) "줄었어요" else "늘었어요"} · 모의"
}

/** Display-only projection of the existing playback frame; no repository writes or engine feedback. */
fun replayAdaptation(trips: List<TripFeatures>, through: LocalDate?, presentation: Boolean): List<AdaptWeek> {
    if (!presentation || through == null) return emptyList()
    val selected = trips.filter {
        it.source == Source.SIM && Instant.ofEpochMilli(it.startMs).atZone(KST).toLocalDate() <= through &&
            Instant.ofEpochMilli(it.endMs).atZone(KST).toLocalDate() <= through
    }
    return Adapt.weekly(selected, KST).takeLast(12)
}

const val ADAPT_DESCRIPTION = "차로 유지 보조와 전방 충돌 경고가 도와준 횟수(100km당)"
const val ADAPT_NOTICE = "모의 · 경고등 읽기는 Pleos 승인 후"
data class AdaptBar(val label: String, val per100km: Double?) {
    val valueText: String get() = per100km?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "—"
}
fun adaptationBars(weeks: List<AdaptWeek>): List<AdaptBar> = weeks.sortedBy { it.weekStart }.takeLast(12).map {
    AdaptBar("${it.weekStart.monthValue}/${it.weekStart.dayOfMonth}", it.per100km?.takeIf { rate -> rate.isFinite() && rate >= 0 })
}
fun adaptationLines(weeks: List<AdaptWeek>): List<String> = if (weeks.isEmpty()) listOf("새 차 적응 기록은 준비 중이에요")
    else listOf(ADAPT_DESCRIPTION, ADAPT_NOTICE) + adaptationBars(weeks).map {
        if (it.per100km == null) "${it.label} 주 · 비교할 기록을 모으고 있어요" else "${it.label} 주 · ${it.valueText}회"
    }

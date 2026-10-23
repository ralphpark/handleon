package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.car.warmstart.BlueLinkMonth
import ai.handleon.driveprint.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

const val HOME_HINT = "집을 즐겨찾기에 등록하면 생활 반경을 볼 수 있어요"
const val DISCLAIMER = "핸들온은 의료기기가 아니며, 운전 습관의 변화를 알려 드릴 뿐 건강 상태를 판단하지 않습니다."
val KST: ZoneId = ZoneId.of("Asia/Seoul")

fun modeForBirthYear(year: Int, today: LocalDate): Mode? = when {
    year !in (today.year - 120)..today.year -> null
    today.year - year >= 60 -> Mode.MAEUM
    else -> Mode.CONDITION
}

data class FirstRunUi(
    val birthYear: String = "", val mode: Mode = Mode.CONDITION,
    val consents: List<Boolean> = listOf(false, false, false), val starting: Boolean = false,
) {
    fun canStart(today: LocalDate): Boolean = !starting && consents.size == 3 && consents.all { it } &&
        birthYear.length == 4 && birthYear.toIntOrNull()?.let { modeForBirthYear(it, today) } != null
    fun withBirthYear(value: String, today: LocalDate): FirstRunUi {
        val year = value.filter(Char::isDigit).take(4)
        return copy(birthYear = year, mode = year.toIntOrNull()?.let { modeForBirthYear(it, today) } ?: mode)
    }
}

data class CollectionProgress(val months: Int, val trips: Int) {
    companion object {
        fun from(trips: List<TripFeatures>): CollectionProgress = CollectionProgress(
            trips.map { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(KST)) }.distinct().size, trips.size)
    }
}

data class ReasonUi(val feature: FeatureId, val line: String, val evidence: String) {
    val domain: Domain get() = feature.domain
    companion object {
        fun from(top: List<Contribution>) = top.take(3).map { ReasonUi(it.feature, Copy.line(it), Copy.evidence(it.feature)) }
    }
}

data class RingUi(val domain: Domain, val fraction: Float?, val over: Boolean)

data class ChartRange(val min: Double, val max: Double) {
    fun fraction(value: Double): Float = ((value - min) / (max - min)).toFloat()
}

fun chartRange(values: List<Double>): ChartRange {
    val finite = values.filter(Double::isFinite)
    return ChartRange((finite.minOrNull() ?: 0.0).coerceAtMost(0.0),
        (finite.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.25)
}

/** The SVG's common dotted radius is at 60% of the 270 degree sweep (297 degrees). */
fun ringFractions(domains: Map<Domain, Double>, thresholds: Map<Domain, Double>): List<RingUi> =
    Domain.entries.map { domain ->
        val value = domains[domain]?.takeIf(Double::isFinite)
        val threshold = thresholds[domain]?.takeIf { it.isFinite() && it > 0 }
        RingUi(domain, if (value == null || threshold == null) null else
            (value / threshold * 0.6).coerceIn(0.0, 1.0).toFloat(),
            value != null && threshold != null && value > threshold)
    }

data class MaeumUi(
    val stage: Stage, val title: String, val onset: String?, val period: String,
    val rings: List<RingUi>, val reasons: List<ReasonUi>, val showCenter: Boolean,
    val collectionText: String?, val homeHint: String?, val months: List<MonthResult>,
    val thresholds: Map<Domain, Double>, val baselineWindows: Map<FeatureId, BaselineWindow>,
    val pastSelected: Boolean = false, val blueLink: List<BlueLinkMonth> = emptyList(),
) {
    companion object {
        fun from(report: LongReport?, home: LatLon?, progress: CollectionProgress? = null): MaeumUi {
            val stage = report?.stage ?: Stage.BUILDING
            val last = report?.months?.lastOrNull()
            return MaeumUi(stage, Copy.stageTitle(stage), report?.onsetWeeksAgo?.let(Copy::onset),
                last?.month?.let { "${it.year}년 ${it.monthValue}월 · 마지막 평가 기록" } ?: "운전 지문 적립 중",
                ringFractions(last?.domains.orEmpty(), report?.thresholds.orEmpty()),
                ReasonUi.from(report?.top.orEmpty()), stage == Stage.TALK,
                if (stage == Stage.BUILDING) progress?.let { "${it.months}개월 · ${it.trips}트립을 모았어요" } else null,
                HOME_HINT.takeIf { home == null }, report?.months.orEmpty(), report?.thresholds.orEmpty(),
                report?.baselineWindows.orEmpty())
        }
    }
}

data class ConditionUi(
    val status: ConditionStatus, val title: String, val days: List<DayResult>,
    val reasons: List<ReasonUi>, val collectedDays: Int, val period: String,
) {
    companion object {
        fun from(report: ConditionReport?, labels: Set<LocalDate> = emptySet(),
                 resets: List<Pair<LocalDate, String>> = emptyList()): ConditionUi {
            val days = report?.days.orEmpty()
            val last = days.lastOrNull()?.day
            // Collection is the available, unlabeled driving days in the current 28-day window.
            val reset = resets.filter { it.second != "HOME_CHANGED" }.maxOfOrNull { it.first }
            val gapStart = days.zipWithNext().lastOrNull { (a, b) ->
                java.time.temporal.ChronoUnit.DAYS.between(a.day, b.day) >= 14
            }?.second?.day
            val count = days.count { day -> last != null && day.day >= last.minusDays(27) &&
                (reset == null || day.day >= reset) && (gapStart == null || day.day >= gapStart) &&
                day.day !in labels && !day.alert && !day.over }.coerceAtMost(7)
            val status = report?.status ?: ConditionStatus.BUILDING
            return ConditionUi(status, Copy.conditionTitle(status), days.takeLast(14), ReasonUi.from(report?.top.orEmpty()),
                count, last?.let { "${it.monthValue}월 ${it.dayOfMonth}일 · 마지막 운전 기록" } ?: "운전 지문 적립 중")
        }
    }
}

enum class DayLabel(val title: String, val detail: String) {
    SLEEP("잠을 설쳤어요", "잠이 부족했던 날"), COLD("감기", "몸이 아팠던 날"),
    NEW_MEDICINE("새 약", "먹기 시작한 약이 있어요"), TRAVEL("여행", "낯선 곳을 다닌 날"),
    CAR_CHANGED("차가 바뀜", "기준을 새로 쌓아요")
}

data class LabelsUi(val today: LocalDate, val yesterday: Boolean = false, val selected: String? = null, val busy: Boolean = false) {
    val day: LocalDate get() = if (yesterday) today.minusDays(1) else today
}
data class SettingsUi(val mode: Mode, val presentation: Boolean, val homeRegistered: Boolean,
                      val confirmDelete: Boolean = false, val busy: Boolean = false,
                      val deletionAvailable: Boolean = COLLECTOR_ERASURE_AVAILABLE)

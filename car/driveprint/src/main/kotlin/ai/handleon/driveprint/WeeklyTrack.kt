package ai.handleon.driveprint

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable enum class WeeklyStatus { BUILDING, OK, FLAGGED }
@Serializable data class WeekResult(
    @Serializable(with = LocalDateSerializer::class) val weekStart: LocalDate,
    val over: Set<Domain>, val flagged: Boolean, val excluded: Boolean,
)
@Serializable data class WeeklyReport(
    val status: WeeklyStatus, val weeks: List<WeekResult>,
    @Serializable(with = LocalDateSerializer::class) val baselineFrom: LocalDate?,
    val weeksUntilReady: Int, val top: List<Contribution>,
)
data class WeekInput(val weekStart: LocalDate, val values: Map<FeatureId, Double>, val trips: Int,
                     val labeled: Boolean, val holiday: Boolean)
fun interface ForecastGate { fun outside(weekStart: LocalDate, feature: FeatureId): Boolean? }

object WeeklyTrack {
    val FEATURES = listOf(FeatureId.M1, FeatureId.M2, FeatureId.M3, FeatureId.M4, FeatureId.M5, FeatureId.M7, FeatureId.W4)
    const val MIN_TRIPS = 3
    const val MIN_BASE = 4
    const val BASE_WEEKS = 8
    const val REANCHOR_WEEKS = 26
    const val COOLDOWN_WEEKS = 4
    const val THRESHOLD_SCALE = 1.0

    fun evaluate(weeks: List<WeekInput>, resets: Set<Reset> = emptySet(), gate: ForecastGate? = null,
                 effect: (LocalDate) -> Double = { 0.0 }): WeeklyReport {
        val ordered = weeks.sortedBy { it.weekStart }
        require(ordered.map { it.weekStart }.distinct().size == ordered.size) { "One input per week is required" }
        val baseline = mutableListOf<WeekInput>()
        val eligibleHistory = mutableListOf<WeekInput>()
        val results = mutableListOf<WeekResult>()
        val resetDays = resets.map { it.day }.distinct().sorted()
        var resetIndex = 0
        var anchor = ordered.firstOrNull()?.weekStart
        var lastFlag: LocalDate? = null
        var previous: WeekResult? = null
        var previousFeatures = emptySet<FeatureId>()
        var top = emptyList<Contribution>()

        for (week in ordered) {
            var resetDay: LocalDate? = null
            while (resetIndex < resetDays.size && resetDays[resetIndex] < week.weekStart.plusWeeks(1)) {
                resetDay = resetDays[resetIndex++]
            }
            if (resetDay != null) {
                baseline.clear()
                eligibleHistory.clear()
                previous = null
                previousFeatures = emptySet()
                lastFlag = null
                anchor = week.weekStart
            }
            val mixedResetWeek = resetDay != null && resetDay > week.weekStart
            if (anchor != null && week.weekStart >= anchor.plusWeeks(REANCHOR_WEEKS.toLong()) && eligibleHistory.size >= MIN_BASE) {
                baseline.clear()
                baseline.addAll(eligibleHistory.takeLast(BASE_WEEKS))
                anchor = week.weekStart
                // Evidence from two different anchors must not form one consecutive pair.
                previous = null
                previousFeatures = emptySet()
            }
            val baselines = if (baseline.size < MIN_BASE) emptyMap() else FEATURES.mapNotNull { f ->
                Stats.baseline(baseline.mapNotNull { it.values[f] }, f.minMad)?.let { f to it }
            }.toMap()
            fun raw(input: WeekInput): Map<FeatureId, Double> = baselines.mapNotNull { (f, b) ->
                input.values[f]?.let { f to Stats.z(it, b) }
            }.toMap()
            fun changes(z: Map<FeatureId, Double>) = z.mapValues { (f, value) -> Scoring.change(f, value) }
            val held = week.labeled || week.holiday || mixedResetWeek || abs(effect(week.weekStart)) >= 0.2
            val z = if (held) emptyMap() else raw(week)
            val changed = changes(z)
            val calibration = baseline.map { changes(raw(it)) }
            val over = Domain.entries.filter { domain ->
                val index = Scoring.domainIndex(changed, domain) ?: return@filter false
                val threshold = Scoring.threshold(calibration.mapNotNull { Scoring.domainIndex(it, domain) })
                index > threshold * THRESHOLD_SCALE
            }.toSet()
            val prior = previous?.takeIf { it.weekStart.plusWeeks(1) == week.weekStart }
            val repeated = over.intersect(prior?.over.orEmpty())
            fun forecastAllows(domain: Domain): Boolean {
                if (gate == null || prior == null) return true
                val pairs = z.keys.intersect(previousFeatures).filter { it.domain == domain }.mapNotNull { f ->
                    val before = gate.outside(prior.weekStart, f)
                    val now = gate.outside(week.weekStart, f)
                    if (before == null || now == null) null else before to now
                }
                return pairs.isEmpty() || pairs.any { (before, now) -> before && now }
            }
            val cooling = lastFlag?.let { week.weekStart <= it.plusWeeks(COOLDOWN_WEEKS.toLong()) } ?: false
            val flagged = !cooling && repeated.any(::forecastAllows)
            if (flagged) lastFlag = week.weekStart
            val excluded = held || week.trips < MIN_TRIPS || flagged
            val result = WeekResult(week.weekStart, over, flagged, excluded)
            results.add(result)
            if (!excluded) {
                eligibleHistory.add(week)
                if (baseline.size < BASE_WEEKS) baseline.add(week)
            }
            previous = result
            previousFeatures = z.keys
            top = Scoring.top(z)
        }
        val left = (MIN_BASE - baseline.size).coerceAtLeast(0)
        return WeeklyReport(when {
            left > 0 -> WeeklyStatus.BUILDING
            results.lastOrNull()?.flagged == true -> WeeklyStatus.FLAGGED
            else -> WeeklyStatus.OK
        }, results, baseline.firstOrNull()?.weekStart, left, top)
    }
}

package ai.handleon.driveprint

import java.time.YearMonth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class MonthInput(val month: YearMonth, val values: Map<FeatureId, Double>, val trips: Int)
@Serializable enum class Stage { BUILDING, STABLE, WATCH, TALK }
@Serializable
data class MonthResult(
    @Serializable(with = YearMonthSerializer::class) val month: YearMonth,
    val domains: Map<Domain, Double>,
    val over: Set<Domain>,
)
@Serializable
data class MonthProgress(@Serializable(with = YearMonthSerializer::class) val month: YearMonth, val trips: Int)
@Serializable
data class BaselineWindow(
    @Serializable(with = YearMonthSerializer::class) val from: YearMonth,
    @Serializable(with = YearMonthSerializer::class) val toExclusive: YearMonth,
)
@Serializable
data class LongReport(
    val stage: Stage, val months: List<MonthResult>, val thresholds: Map<Domain, Double>,
    val baselines: Map<FeatureId, Baseline>, val top: List<Contribution>, val onsetWeeksAgo: Int?,
    val inProgress: MonthProgress? = null,
    val baselineWindows: Map<FeatureId, BaselineWindow> = emptyMap(),
)

object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor = PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: YearMonth) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): YearMonth = YearMonth.parse(decoder.decodeString())
}

object LongTrack {
    fun evaluate(months: List<MonthInput>, resets: Set<Reset> = emptySet()): LongReport {
        val building = LongReport(Stage.BUILDING, emptyList(), emptyMap(), emptyMap(), emptyList(), null)
        if (months.isEmpty()) return building
        val ordered = months.sortedBy { it.month }
        require(ordered.map { it.month }.distinct().size == ordered.size) { "One input per month is required" }
        val last = ordered.last().month
        val applicable = resets.filter { YearMonth.from(it.day) <= last }
        val reset = applicable.filter { it.kind == ResetKind.ALL }.maxOfOrNull { YearMonth.from(it.day) }
        val carReset = applicable.filter { it.kind == ResetKind.CAR_CHANGED }.maxOfOrNull { YearMonth.from(it.day) }
        fun excluded(f: FeatureId, month: YearMonth): Boolean = f == FeatureId.M7 && applicable.any {
            val from = YearMonth.from(it.day)
            it.kind == ResetKind.HOME_CHANGED && month >= from && month < from.plusMonths(6)
        }
        val active = ordered.filter { reset == null || it.month >= reset }
        val baselines = mutableMapOf<FeatureId, Baseline>()
        val windows = mutableMapOf<FeatureId, BaselineWindow>()
        for (feature in FeatureId.entries) {
            val observed = active.filter { feature in it.values && !excluded(feature, it.month) &&
                (feature.domain != Domain.OPERATION || carReset == null || it.month >= carReset) }
            val first = observed.firstOrNull()?.month ?: continue
            var end = first.plusMonths(6)
            while (end <= last.plusMonths(1) && end <= first.plusMonths(9)) {
                val sample = observed.filter { it.month < end }
                val period = active.filter { it.month >= first && it.month < end }
                if (sample.size >= 4 && period.sumOf { it.trips.toLong() } >= 50) {
                    baselines[feature] = Stats.baseline(sample.map { it.values.getValue(feature) }, feature.minMad)!!
                    windows[feature] = BaselineWindow(first, end)
                    break
                }
                end = end.plusMonths(1)
            }
        }
        if (baselines.isEmpty()) return building
        fun changes(m: MonthInput, features: Set<FeatureId>): Map<FeatureId, Double> = m.values
            .filterKeys { it in features && !excluded(it, m.month) }.mapValues { (f, v) -> Scoring.change(f, Stats.z(v, baselines.getValue(f))) }
        fun domains(scores: Map<FeatureId, Double>): Map<Domain, Double> = Domain.entries.mapNotNull { d ->
            Scoring.domainIndex(scores, d)?.let { d to it }
        }.toMap()
        // A late feature neither uses earlier missing months nor rewrites older scored months.
        val thresholdCache = mutableMapOf<Set<FeatureId>, Map<Domain, Double>>()
        fun thresholds(features: Set<FeatureId>): Map<Domain, Double> = thresholdCache.getOrPut(features) {
            Domain.entries.mapNotNull { domain ->
                val ready = features.filter { it.domain == domain }.toSet()
                if (ready.isEmpty()) return@mapNotNull null
                val calibration = active.filter { month -> ready.all { feature ->
                    val window = windows.getValue(feature)
                    month.month >= window.from && month.month < window.toExclusive
                } }.mapNotNull { month -> Scoring.domainIndex(changes(month, ready), domain) }
                domain to if (calibration.size < 3) 1.5 else Scoring.threshold(calibration)
            }.toMap()
        }
        val results = active.mapNotNull { m ->
            val ready = windows.filter { (f, w) -> m.month >= w.toExclusive && !excluded(f, m.month) }.keys
            val indices = domains(changes(m, ready))
            if (indices.isEmpty()) return@mapNotNull null
            val limits = thresholds(ready)
            MonthResult(m.month, indices, indices.filter { (d, value) -> value > limits.getValue(d) }.keys)
        }
        val current = results.lastOrNull()
        val previous = results.dropLast(1).lastOrNull()
        val stage = when {
            current == null -> Stage.BUILDING
            previous != null && current.over.intersect(previous.over).size >= 2 -> Stage.TALK
            current.over.isNotEmpty() -> Stage.WATCH
            else -> Stage.STABLE
        }
        val raw = current?.let { result -> active.first { it.month == result.month }.values.mapNotNull { (f, value) ->
            windows[f]?.takeIf { result.month >= it.toExclusive && !excluded(f, result.month) }?.let { f to Stats.z(value, baselines.getValue(f)) }
        }.toMap() }.orEmpty()
        val currentFeatures = current?.let { result -> windows.filter { (f, w) ->
            result.month >= w.toExclusive && !excluded(f, result.month)
        }.keys } ?: baselines.keys
        return LongReport(stage, results, thresholds(currentFeatures), baselines, Scoring.top(raw), null,
            baselineWindows = windows)
    }
}

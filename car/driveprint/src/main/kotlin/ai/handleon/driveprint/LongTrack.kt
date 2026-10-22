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
data class LongReport(
    val stage: Stage, val months: List<MonthResult>, val thresholds: Map<Domain, Double>,
    val baselines: Map<FeatureId, Baseline>, val top: List<Contribution>, val onsetWeeksAgo: Int?,
)

object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor = PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: YearMonth) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): YearMonth = YearMonth.parse(decoder.decodeString())
}

object LongTrack {
    fun evaluate(months: List<MonthInput>, resetMonths: Set<YearMonth> = emptySet()): LongReport {
        val building = LongReport(Stage.BUILDING, emptyList(), emptyMap(), emptyMap(), emptyList(), null)
        if (months.isEmpty()) return building
        val ordered = months.sortedBy { it.month }
        require(ordered.map { it.month }.distinct().size == ordered.size) { "One input per month is required" }
        val last = ordered.last().month
        val reset = resetMonths.filter { it <= last }.maxOrNull()
        val start = maxOf(ordered.first().month, reset ?: ordered.first().month)
        val active = ordered.filter { it.month >= start }
        var baselineEnd = start.plusMonths(6)
        var baselineMonths: List<MonthInput>
        while (true) {
            if (baselineEnd > last.plusMonths(1)) return building
            baselineMonths = active.filter { it.month < baselineEnd && it.trips > 0 }
            if (baselineMonths.size >= 4 && baselineMonths.sumOf { it.trips.toLong() } >= 50) break
            baselineEnd = baselineEnd.plusMonths(1)
        }
        val baselines = FeatureId.entries.mapNotNull { feature ->
            Stats.baseline(baselineMonths.mapNotNull { it.values[feature] })?.let { feature to it }
        }.toMap()
        if (baselines.isEmpty()) return building
        fun raw(m: MonthInput): Map<FeatureId, Double> = if (m.trips <= 0) emptyMap() else
            m.values.mapNotNull { (f, value) -> baselines[f]?.let { f to Stats.z(value, it) } }.toMap()
        fun domains(m: MonthInput): Map<Domain, Double> {
            val changes = raw(m).mapValues { (f, z) -> Scoring.change(f, z) }
            return Domain.entries.mapNotNull { d -> Scoring.domainIndex(changes, d)?.let { d to it } }.toMap()
        }
        val baselineDomains = baselineMonths.map(::domains)
        val thresholds = Domain.entries.mapNotNull { d ->
            baselineDomains.mapNotNull { it[d] }.takeIf { it.isNotEmpty() }?.let { d to Scoring.threshold(it) }
        }.toMap()
        val results = active.filter { it.month >= baselineEnd }.map { m ->
            val indices = domains(m)
            MonthResult(m.month, indices, indices.filter { (d, value) ->
                thresholds[d]?.let { value > it } == true
            }.keys)
        }
        val current = results.lastOrNull()
        val previous = results.dropLast(1).lastOrNull()
        val stage = when {
            current != null && previous != null && previous.month.plusMonths(1) == current.month &&
                current.over.intersect(previous.over).size >= 2 -> Stage.TALK
            current != null && current.over.isNotEmpty() -> Stage.WATCH
            else -> Stage.STABLE
        }
        return LongReport(stage, results, thresholds, baselines,
            if (current == null) emptyList() else Scoring.top(raw(active.last())), null)
    }
}

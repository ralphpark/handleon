package ai.handleon.sim

import ai.handleon.driveprint.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Summary(
    val sensitivity: Double, val falseAlarmsPerPersonYear: Double, val medianLeadMonths: Double?,
    val drivers: Int, val months: Int, val seed: Long, val assumptions: List<String>,
    val meetsAcceptanceGate: Boolean, val perPersona: List<PersonaOutcome>,
    val normalWatchMonthRatio: Double, val normalWatchPersonRatio: Double,
    val ruleComparison: Map<String, CohortMetrics>, val lifestyleVariant: CohortResult,
)

fun main(args: Array<String>) {
    require(args.size % 2 == 0) { "Options require values" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys.all { it in setOf("--drivers", "--months", "--seed", "--out", "--persona", "--search-father-seeds") }) { "Unknown option" }
    options["--search-father-seeds"]?.let { value ->
        require(options.size == 1) { "Seed search only accepts --search-father-seeds" }
        val lastSeed = value.toLong()
        require(lastSeed in 1L..200L)
        FatherSeedSearch.search(1L..lastSeed).forEach { (rule, candidates) ->
            println("rule=$rule candidates=${candidates.size}")
            candidates.forEach { println("seed=${it.seed} firstTalk=${it.firstTalk} lastStage=${it.lastStage} onsetWeeksAgo=${it.onsetWeeksAgo}") }
        }
        return
    }
    val out = File(options["--out"] ?: "build/sim")
    options["--persona"]?.let { name ->
        require(options.keys.all { it in setOf("--persona", "--out") }) { "Replay personas have fixed parameters" }
        val trips = when (name) {
            "father" -> Personas.father()
            "child" -> Personas.child()
            else -> error("Unknown persona: $name")
        }
        require(out.isDirectory || out.mkdirs()) { "Cannot create output directory" }
        val file = File(out, "$name.json")
        file.writeText(Json.encodeToString(Replay(1, name, trips)))
        val engine = Driveprint()
        if (name == "father") {
            val firstTalk = (0L..23L).map { YearMonth.of(2024, 11).plusMonths(it) }
                .firstOrNull { engine.longReport(trips, emptySet(), emptySet(), it.plusMonths(1).atDay(1)).stage == Stage.TALK }
            val report = engine.longReport(trips, emptySet(), emptySet(), YearMonth.of(2026, 11).atDay(1))
            println("seed=${Personas.FATHER_SEED} firstTalk=$firstTalk finalStage=${report.stage} onsetWeeksAgo=${report.onsetWeeksAgo}")
            report.months.filter { it.month >= YearMonth.of(2026, 5) }.forEach {
                println("month=${it.month} domains=${it.domains}")
            }
        } else {
            println("lastDayStatus=" + engine.conditionReport(trips, emptySet(), emptySet(), LocalDate.of(2026, 10, 22)).status)
        }
        println("output=" + file.canonicalPath + " bytes=" + file.length())
        return
    }
    val drivers = options["--drivers"]?.toInt() ?: 200
    val months = options["--months"]?.toInt() ?: CohortRun.DEFAULT_MONTHS
    val seed = options["--seed"]?.toLong() ?: 7L
    require(out.isDirectory || out.mkdirs()) { "Cannot create output directory" }
    val result = CohortRun.run(drivers, months, seed)
    val lifestyle = CohortRun.run(drivers, months, seed, lifestyleVariant = true)
    val accepted = result.sensitivity >= 0.5 && result.falseAlarmsPerPersonYear <= 0.3
    val summary = Summary(result.sensitivity, result.falseAlarmsPerPersonYear, result.medianLeadMonths,
        drivers, months, seed, Generator.assumptions, accepted, result.perPersona,
        result.normalWatchMonthRatio, result.normalWatchPersonRatio, result.ruleComparison, lifestyle)
    File(out, "summary.json").writeText(Json { prettyPrint = true }.encodeToString(summary))
    File(out, "detection.svg").writeText(Svg.bars("합성 변화군 탐지 비율", listOf("민감도"), listOf(result.sensitivity)))
    File(out, "false_alarms.svg").writeText(Svg.bars("정상군 TALK 진입 / 인년", listOf("오경보"), listOf(result.falseAlarmsPerPersonYear)))
    val lead = result.medianLeadMonths
    File(out, "lead_time.svg").writeText(if (lead == null) Svg.bars("선행 시간: 관측 없음", emptyList(), emptyList())
        else Svg.bars("중앙 선행 시간 (개월)", listOf("개월"), listOf(lead)))
    val example = result.perPersona.first { it.declineOnset != null }
    File(out, "example_persona.svg").writeText(Svg.lines("변화군 예시 영역 지수",
        example.monthly.map { it.month.toString() },
        Domain.entries.associate { d -> d.name to example.monthly.map { it.domains[d] } }))
    println("sensitivity=" + result.sensitivity + " falseAlarmsPerPersonYear=" + result.falseAlarmsPerPersonYear +
        " medianLeadMonths=" + result.medianLeadMonths)
    println("output=" + out.canonicalPath + " acceptanceGate=" + accepted)
    println("normalWatchMonthRatio=${result.normalWatchMonthRatio} normalWatchPersonRatio=${result.normalWatchPersonRatio}")
    println("lifestyleVariant=" + lifestyle.ruleComparison.getValue("D"))
    result.ruleComparison.forEach { (rule, metrics) -> println("rule=$rule $metrics") }
}

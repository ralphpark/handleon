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
)

fun main(args: Array<String>) {
    require(args.size % 2 == 0) { "Options require values" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys.all { it in setOf("--drivers", "--months", "--seed", "--out", "--persona") }) { "Unknown option" }
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
                .firstOrNull { engine.longReport(trips, emptySet(), emptySet(), it).stage == Stage.TALK }
            println("firstTalk=$firstTalk finalStage=" +
                engine.longReport(trips, emptySet(), emptySet(), YearMonth.of(2026, 10)).stage)
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
    val accepted = result.sensitivity >= 0.5 && result.falseAlarmsPerPersonYear <= 0.3
    val summary = Summary(result.sensitivity, result.falseAlarmsPerPersonYear, result.medianLeadMonths,
        drivers, months, seed, Generator.assumptions, accepted, result.perPersona)
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
}

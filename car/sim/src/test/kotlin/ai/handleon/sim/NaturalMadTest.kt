package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.*

class NaturalMadTest {
    @Test fun minMadDoesNotDominateNormalDriverFloor() {
        val start = YearMonth.of(2024, 11)
        val trips = Generator.trips(Persona(1, null, 60.0, 12, 30.0, 7), start, 6)
        val monthly = (0L until 6).map { offset ->
            val month = start.plusMonths(offset)
            Periods.values(trips, month.atDay(1), month.plusMonths(1).atDay(1), ZoneId.of("Asia/Seoul"))
        }
        val natural = FeatureId.entries.associateWith { feature ->
            val values = monthly.mapNotNull { it[feature] }
            assertTrue(values.size >= 3, "$feature has ${values.size} months")
            val center = Stats.median(values)
            Stats.median(values.map { abs(it - center) })
        }
        // minMad 는 기준선이 0·상수일 때만 막는 장치다. 정상 운전자에게는 원래 하한 max(MAD, 0.1·|중앙값|) 의 4배를 넘으면 안 된다.
        // (첫 수정의 W4 0.1 같은 값은 여기서 걸린다 — 진단 보고서 2026-10-22-fable-engine-fix-diagnosis.md)
        val floors = natural.mapValues { (feature, mad) ->
            val center = Stats.median(monthly.mapNotNull { it[feature] })
            maxOf(mad, 0.1 * abs(center), 0.01)
        }
        floors.forEach { (feature, floor) -> println("$feature floor=$floor minMad=${feature.minMad}") }
        assertTrue(floors.all { (feature, floor) -> feature.minMad <= 4 * floor },
            "minMad dominates normal floor: ${floors.filter { (feature, floor) -> feature.minMad > 4 * floor }}")
    }
}

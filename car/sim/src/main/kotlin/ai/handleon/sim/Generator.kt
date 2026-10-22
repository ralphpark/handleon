package ai.handleon.sim

import ai.handleon.driveprint.*
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.*

object Generator {
    val assumptions = listOf(
        "선행 시간 기준점 = 시작+12개월(가정). 임상 전환 시점을 관찰한 값이 아님.",
        "생활 변화 변형 실행: 정상군 20%(ID 순 첫 20%, 소수점 버림)는 13번째 달부터 월 트립 ×0.8 영구 감소.",
        "변형은 같은 생성 결과에서 월별 고정 시드 추출로 floor(트립수×0.8)개를 유지하며, 남은 트립의 지표는 변경하지 않음.",
        "WATCH 월 비율 분모는 정상군의 평가된 달, WATCH 경험자 비율 분모는 정상군 인원. TALK 달은 WATCH로 세지 않음.",
        "가정값: 정상군 100명, 변화군 100명 (200명 실행 기준). 임상 검증이 아니라 엔진 동작 검증.",
        "월 트립 λ ~ U(30, 70), 장소 수 ~ U(8, 20), 목적지 지프 분포.",
        "겨울(12~2월) 트립 ×0.85, 설·추석 달 장거리 트립 +2.",
        "감기 주간 연 1~2회, 그 주 트립 ×0.5, 라벨 없음.",
        "장마(7~8월) 비 확률 0.35·그 외 0.12, 야간 확률 0.15(겨울 +0.10).",
        "변화 시작은 U(10, 16)번째 달, 시작 시 변화량 0에서 6개월에 걸쳐 직선 변화.",
        "변화 후 트립 ×0.75, 장소 수 ×0.7, 집에서 최대 거리 ×0.7, 야간 확률 ×0.5.",
        "익숙한 길 재탐색 ×2.0, 익숙한 곳 안내 사용 0.05 → 0.25.",
        "방향지시등 누락 0.10 → 0.20, 좌회전 비중 0.45 → 0.38.",
        "조작 지표는 운전자별 평균이 다른 정규분포에서 트립마다 생성.",
        "계획 미지정 세부 가정: 월 λ를 일별 Poisson으로 분배, Zipf 지수 1, 집 거리 척도 U(10,40)km.",
        "일반 거리 U(2,20)km, 명절 추가 거리 U(60,180)km; 모든 목적지는 익숙한 곳.",
        "안내 중 재탐색 Poisson(안내 km × 0.05 × 변화배수), 트립 회전 2~7회 이항분포.",
        "운전자 평균: 과속 U(.01,.10), 급제동 U(1,5)/100km, 조향 U(.6,2), LKA U(0,2)/100km.",
        "트립별 정규 잡음 SD: 과속 .02, 급제동 1/100km, 조향 평균×.20, LKA .5/100km; 비음수 제한 및 횟수 반올림.",
        "민감도 분모는 모든 변화군; 관측 종료로 시작+12개월까지 추적 못한 운전자도 포함하며 censored로 표시.",
    )

    fun trips(p: Persona, start: YearMonth, months: Int): List<TripFeatures> {
        require(months >= 0 && p.tripsPerMonth > 0 && p.places > 0 && p.homeKmScale > 0)
        val random = Random(p.seed)
        val zone = ZoneId.of("Asia/Seoul")
        val speedMean = 0.01 + random.nextDouble() * 0.09
        val brakeMean = 1 + random.nextDouble() * 4
        val steeringMean = 0.6 + random.nextDouble() * 1.4
        val lkaMean = random.nextDouble() * 2
        val result = mutableListOf<TripFeatures>()
        val coldByYear = mutableMapOf<Int, Set<Int>>()
        for (offset in 0 until months) {
            val month = start.plusMonths(offset.toLong())
            val winter = month.monthValue in listOf(12, 1, 2)
            val progress = p.declineOnset?.let { (ChronoUnit.MONTHS.between(it, month) / 6.0).coerceIn(0.0, 1.0) } ?: 0.0
            val places = (p.places * (1 - 0.3 * progress)).roundToInt().coerceAtLeast(1)
            val cold = coldByYear.getOrPut(month.year) {
                val r = Random(p.seed xor (month.year.toLong() * 1_000_003))
                val count = 1 + r.nextInt(2)
                buildSet { while (size < count) add(r.nextInt(52)) }
            }
            fun make(day: Int, extraLong: Boolean): TripFeatures {
                val night = random.nextDouble() < (if (winter) 0.25 else 0.15) * (1 - 0.5 * progress)
                val rain = random.nextDouble() < if (month.monthValue in 7..8) 0.35 else 0.12
                val distance = if (extraLong) 60 + random.nextDouble() * 120 else 2 + random.nextDouble() * 18
                val startMs = month.atDay(day).atTime(if (night) 21 else 10 + random.nextInt(6), random.nextInt(60), random.nextInt(60))
                    .atZone(zone).toInstant().toEpochMilli()
                val destination = zipf(random, places)
                val guided = random.nextDouble() < 0.05 + 0.20 * progress
                val turns = 2 + random.nextInt(6)
                val left = binomial(random, turns, 0.45 - 0.07 * progress)
                val announced = if (guided) turns else 0
                val radius = p.homeKmScale * (1 - 0.3 * progress) *
                    (if (extraLong) 2 + random.nextDouble() else 0.3 + 0.7 * random.nextDouble())
                return TripFeatures(startMs, startMs + (distance / 35 * 3_600_000).roundToLong(), Source.SIM,
                    distance, night, rain, KoreanHolidays.isOffDay(month.atDay(day)),
                    p.id * 100L + destination, radius, true, guided, if (guided) distance else 0.0,
                    if (guided) poisson(random, distance * 0.05 * (1 + progress)) else 0,
                    left, turns - left, announced, binomial(random, announced, 0.10 + 0.10 * progress),
                    (speedMean + random.nextGaussian() * 0.02).coerceIn(0.0, 1.0),
                    ((brakeMean + random.nextGaussian()) * distance / 100).roundToInt().coerceAtLeast(0),
                    (steeringMean + random.nextGaussian() * steeringMean * 0.20).coerceAtLeast(0.0),
                    (6 + random.nextGaussian()).coerceAtLeast(0.0),
                    ((lkaMean + random.nextGaussian() * 0.5) * distance / 100).roundToInt().coerceAtLeast(0), 0)
            }
            for (day in 1..month.lengthOfMonth()) {
                val date = month.atDay(day)
                val lambda = p.tripsPerMonth / month.lengthOfMonth() * (if (winter) 0.85 else 1.0) *
                    (1 - 0.25 * progress) * (if ((date.dayOfYear - 1) / 7 in cold) 0.5 else 1.0)
                repeat(poisson(random, lambda)) { result.add(make(day, false)) }
            }
            val lunarMonths = when (month.year) {
                2023 -> setOf(1, 9)
                2024, 2026, 2027 -> setOf(2, 9)
                2025 -> setOf(1, 10)
                else -> emptySet()
            }
            if (month.monthValue in lunarMonths) repeat(2) { result.add(make(1 + random.nextInt(month.lengthOfMonth()), true)) }
        }
        return result.sortedBy { it.startMs }
    }

    private fun poisson(random: Random, lambda: Double): Int {
        val limit = exp(-lambda)
        var product = 1.0
        var count = 0
        do { count++; product *= random.nextDouble() } while (product > limit)
        return count - 1
    }
    private fun binomial(random: Random, n: Int, p: Double) = (0 until n).count { random.nextDouble() < p }
    private fun zipf(random: Random, places: Int): Int {
        var choice = random.nextDouble() * (1..places).sumOf { 1.0 / it }
        for (place in 1..places) { choice -= 1.0 / place; if (choice <= 0) return place }
        return places
    }
}

package ai.handleon.car.ui

import ai.handleon.driveprint.*

fun readinessText(r: Readiness): String {
    if (r.weeklyWeeksLeft <= 0 && r.monthlyFrom == null && r.monthlyTripsLeft <= 0) return "평소와 비교하고 있어요"
    val weekly = if (r.weeklyWeeksLeft > 0) "주간 비교까지 ${r.weeklyWeeksLeft}주 더" else "주간 비교는 준비됐어요"
    val monthly = r.monthlyFrom?.let { "월간 비교는 ${it.year}년 ${it.monthValue}월부터" }
        ?: if (r.monthlyTripsLeft > 0) "월간 비교를 위한 운전 기록을 모으고 있어요" else "월간 비교는 준비됐어요"
    return "$weekly · $monthly"
}
fun weeklyText(report: WeeklyReport): String? {
    if (report.status != WeeklyStatus.FLAGGED) return null
    return report.weeks.maxByOrNull { it.weekStart }?.over?.sortedBy { it.ordinal }?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { when (it) {
            Domain.MOBILITY -> "요즘 몇 주 평소보다 가까운 곳만 다니셨어요"
            Domain.WAYFINDING -> "요즘 몇 주 익숙한 길에서도 길안내를 더 자주 켜셨어요"
            Domain.OPERATION -> "요즘 몇 주 운전하는 모습이 평소와 조금 달랐어요"
        } }
}

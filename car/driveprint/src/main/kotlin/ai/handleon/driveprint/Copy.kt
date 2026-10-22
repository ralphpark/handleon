package ai.handleon.driveprint

object Copy {
    val FORBIDDEN = listOf("치매", "인지장애", "진단", "위험", "점수", "질환", "판정", "우울")

    fun line(c: Contribution): String {
        val down = c.z < 0
        val count = if (down) "줄었어요" else "늘었어요"
        return when (c.feature) {
            FeatureId.M1 -> "차로 외출하시는 횟수가 평소보다 $count"
            FeatureId.M2 -> "운전하시는 날이 평소보다 $count"
            FeatureId.M3 -> "가까운 동네를 벗어나 다녀오시는 일이 평소보다 $count"
            FeatureId.M4 -> "해가 진 뒤 운전하시는 비중이 평소보다 $count"
            FeatureId.M5 -> "가시는 곳의 가짓수가 평소보다 $count"
            FeatureId.M6 -> if (down) "자주 가시는 곳이 전보다 몇 곳에 모였어요" else "자주 가시는 곳이 전보다 다양해졌어요"
            FeatureId.M7 -> if (down) "집에서 다녀오시는 거리가 평소보다 가까워졌어요" else "집에서 다녀오시는 거리가 평소보다 멀어졌어요"
            FeatureId.W2 -> "좌회전하시는 비중이 평소보다 $count"
            FeatureId.W3 -> "자주 가시던 곳으로 가는 길에서 길을 다시 찾는 일이 $count"
            FeatureId.W4 -> "익숙한 곳에 가실 때 내비를 켜시는 일이 평소보다 $count"
            FeatureId.O1 -> "제한속도보다 빠르게 달리시는 시간이 평소보다 $count"
            FeatureId.O2 -> "속도를 갑자기 줄이시는 일이 평소보다 $count"
            FeatureId.O7 -> if (down) "곧은 길에서 핸들이 평소보다 덜 흔들렸어요" else "곧은 길에서 핸들이 평소보다 많이 흔들렸어요"
            FeatureId.O8 -> "내비가 알려 준 회전에서 방향지시등을 켜지 않은 일이 $count"
            FeatureId.O9 -> "차로를 지키도록 차가 도와준 일이 평소보다 $count"
        }
    }

    fun evidence(f: FeatureId): String = if (f.grade == Grade.C) "Pleos 지표, 검증 중" else "연구 근거 있는 지표"
    fun stageTitle(s: Stage): String = when (s) {
        Stage.BUILDING -> "운전 지문을 모으는 중이에요"
        Stage.STABLE -> "평소와 비슷해요"
        Stage.WATCH -> "평소와 조금 달라진 점이 있어요"
        Stage.TALK -> "가족과 이야기 나눠 보시면 좋겠어요"
    }
    fun conditionTitle(s: ConditionStatus): String = when (s) {
        ConditionStatus.BUILDING -> "기준을 쌓는 중이에요"
        ConditionStatus.OK -> "오늘 운전 컨디션 괜찮아요"
        ConditionStatus.CAUTION -> "오늘은 쉬어 가시면 좋겠어요"
        ConditionStatus.HOLD -> "오늘은 밤·비 운전이라 판단을 쉬어요"
    }
    fun onset(weeksAgo: Int): String = "약 " + weeksAgo + "주 전부터 달라졌어요"
}

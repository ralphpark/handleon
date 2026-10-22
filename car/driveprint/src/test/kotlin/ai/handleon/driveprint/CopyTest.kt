package ai.handleon.driveprint

import kotlin.test.*

class CopyTest {
    @Test fun noForbiddenWordsAnywhere() {
        val all = FeatureId.entries.flatMap { listOf(Copy.line(Contribution(it, 2.0, 1.0)), Copy.line(Contribution(it, -2.0, 1.0)), Copy.evidence(it)) } +
            Stage.entries.map(Copy::stageTitle) + ConditionStatus.entries.map(Copy::conditionTitle) + Copy.onset(6)
        all.forEach { s -> Copy.FORBIDDEN.forEach { w -> assertFalse(w in s, "'$w' in '$s'") } }
    }
    @Test fun cGradeSaysUnverified() = assertEquals("Pleos 지표, 검증 중", Copy.evidence(FeatureId.W3))
    @Test fun directionChangesWording() = assertNotEquals(Copy.line(Contribution(FeatureId.M5, -2.0, 2.0)), Copy.line(Contribution(FeatureId.M5, 2.0, 2.0)))
    @Test fun fixedTitlesAndOnsetMatchThePlan() {
        assertEquals(listOf("운전 지문을 모으는 중이에요", "평소와 비슷해요", "평소와 조금 달라진 점이 있어요", "가족과 이야기 나눠 보시면 좋겠어요"), Stage.entries.map(Copy::stageTitle))
        assertEquals(listOf("기준을 쌓는 중이에요", "오늘 운전 컨디션 괜찮아요", "오늘은 쉬어 가시면 좋겠어요", "오늘은 밤·비 운전이라 판단을 쉬어요"), ConditionStatus.entries.map(Copy::conditionTitle))
        assertEquals("약 6주 전부터 달라졌어요", Copy.onset(6))
        assertEquals("가시는 곳의 가짓수가 평소보다 줄었어요", Copy.line(Contribution(FeatureId.M5, -2.0, 2.0)))
    }
}

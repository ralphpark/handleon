package ai.handleon.car.ui

import ai.handleon.car.sync.FedUi
import kotlin.test.*

class FedSettingsTest {
    @Test fun `federation starts off and virtual clients are explicitly mock only in presentation`() {
        val ui = FedUi()
        assertFalse(ui.enabled)
        assertContains(fedSettingsLines(ui, false), "다른 차와 숫자 모델만 나눠요. 위치나 운전 기록은 보내지 않아요")
        assertFalse(fedSettingsLines(ui, false).any { "가상 차" in it })
        assertContains(fedSettingsLines(ui, true), "가상 차 2대 · 모의")
        for (line in fedSettingsLines(ui, true)) for (word in listOf("점수", "등급", "진단", "위험", "면허 반납")) assertFalse(word in line)
        assertTrue(showDrivingScreen(true, true, Screen.Settings))
    }
}

package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ResumeScreen(ui: ResumeUi, onBack: () -> Unit, onPromises: () -> Unit) {
    ScreenFrame {
        PageHeader("내 운전 이력서", onBack)
        val headings = setOf("이번 주", "이번 달", "운전 약속", "새 차 적응", "이전 차와 비교")
        for (line in resumeLines(ui)) Text(line, color = DarkTokens.ink,
            style = if (line in headings) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
        PillButton("운전 약속", onPromises)
    }
}

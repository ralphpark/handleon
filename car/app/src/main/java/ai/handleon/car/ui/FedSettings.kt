package ai.handleon.car.ui

import ai.handleon.car.sync.FedUi
import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

fun fedSettingsLines(ui: FedUi, presentation: Boolean): List<String> = buildList {
    add("다른 차와 숫자 모델만 나눠요. 위치나 운전 기록은 보내지 않아요")
    if (presentation) add("가상 차 2대 · 모의")
    ui.modelRound?.let { add(if (it == 0) "함께 받은 모델은 준비 중이에요" else "함께 받은 모델 · ${it}번째") }
    if (presentation) ui.submitted?.let { add("이번 실행에서 숫자 모델 ${it}개를 보냈어요 · 모의") }
    if (ui.pendingOff) add("이 차의 참여는 꺼졌어요. 서버에 끄기 요청을 다시 보낼게요")
    ui.error?.let(::add)
}

@Composable
fun FedSettingsPanel(ui: FedUi, presentation: Boolean, connected: Boolean, busy: Boolean,
                     onToggle: (Boolean) -> Unit, onRound: () -> Unit, onRefresh: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 함께 배우기", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
            val canToggle = connected && !busy && (!ui.busy || ui.enabled)
            Row(Modifier.heightIn(min = 64.dp).semantics { toggleableState = if (ui.enabled) ToggleableState.On else ToggleableState.Off }
                .midnightClick(canToggle, Role.Switch) { onToggle(!ui.enabled) }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (ui.enabled) "켜짐" else "꺼짐", color = DarkTokens.inkMuted)
                Switch(ui.enabled, onCheckedChange = null, enabled = canToggle)
            }
        }
        fedSettingsLines(ui, presentation).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
        if (!connected) Text("휴대폰을 연결한 뒤 선택할 수 있어요", color = DarkTokens.inkMuted)
        if (ui.busy) Text("숫자 모델을 확인하고 있어요", color = DarkTokens.inkMuted)
        if (presentation) {
            PillButton("한 라운드 보내기 · 모의", onRound, kind = PillKind.SECONDARY, enabled = connected && ui.enabled && !ui.busy && !busy)
            PillButton("전역 모델 새로 받기", onRefresh, kind = PillKind.SECONDARY, enabled = connected && !ui.busy && !busy)
        }
    }
}

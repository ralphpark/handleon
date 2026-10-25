package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
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

@Composable
fun SettingsScreen(ui: SettingsUi, onMode: (Mode) -> Unit, onReplay: () -> Unit, onPresentation: (Boolean) -> Unit,
                   onDeleteDialog: (Boolean) -> Unit, onDelete: () -> Unit, onBack: () -> Unit,
                   onNewCode: () -> Unit = {}, onSync: () -> Unit = {}, onDevEngine: () -> Unit = {},
                   onAlwaysLocation: () -> Unit = {}, onSharingToggle: () -> Unit = {},
                   onRevokeDialog: (Boolean) -> Unit = {}, onRevoke: () -> Unit = {}) {
    ScreenFrame { wide ->
        PageHeader("설정", onBack)
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { SettingsRows(ui, onMode, onReplay, onPresentation, onDeleteDialog, onDevEngine, onAlwaysLocation) }
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (ui.pairing.driverId != null) SharingPanel(ui.sharing, ui.sharingBusy, ui.sharingError, ui.confirmRevoke,
                    onSharingToggle, onRevokeDialog, onRevoke)
                GlassPanel(Modifier.fillMaxWidth(), padding = 44.dp) { PairingScreen(ui.pairing, onNewCode, onSync) }
            }
        } else {
            SettingsRows(ui, onMode, onReplay, onPresentation, onDeleteDialog, onDevEngine, onAlwaysLocation)
            if (ui.pairing.driverId != null) SharingPanel(ui.sharing, ui.sharingBusy, ui.sharingError, ui.confirmRevoke,
                onSharingToggle, onRevokeDialog, onRevoke)
            GlassPanel(Modifier.fillMaxWidth()) { PairingScreen(ui.pairing, onNewCode, onSync) }
        }
    }
    if (ui.confirmDelete) AlertDialog(onDismissRequest = { if (!ui.busy) onDeleteDialog(false) },
        title = { Text("내 데이터를 모두 지울까요?", style = MaterialTheme.typography.titleLarge) },
        text = { Text("이 차에 모인 운전 지문과 라벨이 지워지고 첫 실행 화면으로 돌아가요.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { PillButton(if (ui.busy) "지우는 중이에요" else "모두 지우기", onDelete, kind = PillKind.CORAL, enabled = !ui.busy && ui.deletionAvailable) },
        dismissButton = { PillButton("돌아가기", { onDeleteDialog(false) }, kind = PillKind.SECONDARY, enabled = !ui.busy) })
}
@Composable
private fun SettingsRows(ui: SettingsUi, onMode: (Mode) -> Unit, onReplay: () -> Unit,
                         onPresentation: (Boolean) -> Unit, onDeleteDialog: (Boolean) -> Unit, onDevEngine: () -> Unit,
                         onAlwaysLocation: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("모드", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        Spacer(Modifier.height(16.dp))
        ModeChoices(ui.mode, onMode, !ui.busy)
    }
    SettingRow("휴대폰 연결", if (ui.pairing.driverId == null) "연결된 휴대폰 없음" else "휴대폰과 연결됐어요") {}
    // 위치가 '항상 허용'이어야 앱을 열지 않아도 시동 직후부터 모을 수 있다(안드로이드 14 백그라운드 제한).
    SettingRow("시동 직후부터 모으기", if (ui.alwaysLocation) "앱을 열지 않아도 시동을 걸면 바로 모아요"
        else "위치를 '항상 허용'으로 바꾸면 앱을 열지 않아도 모아요") {
        if (ui.alwaysLocation) Text("켜짐", style = MaterialTheme.typography.labelLarge, color = DarkTokens.brand)
        else PillButton("켜기", onAlwaysLocation, kind = PillKind.SECONDARY, enabled = !ui.busy)
    }
    SettingRow("집 위치", if (ui.homeRegistered) "내비 즐겨찾기 '집'에서 가져왔어요" else "내비 즐겨찾기에 집을 등록해 주세요") {
        Text(if (ui.homeRegistered) "등록됨" else "미등록", style = MaterialTheme.typography.labelLarge, color = if (ui.homeRegistered) DarkTokens.brand else DarkTokens.inkMuted)
    }
    SettingRow("24개월을 60초로 보기", "가상 인물의 운전 변화를 살펴봐요") { PillButton("재생", onReplay, kind = PillKind.REPLAY, enabled = !ui.busy) }
    SettingRow("발표 모드", "시연용 설정을 저장해요") {
        Row(Modifier.heightIn(min = 64.dp).semantics { toggleableState = if (ui.presentation) ToggleableState.On else ToggleableState.Off }
            .midnightClick(!ui.busy, Role.Switch) { onPresentation(!ui.presentation) }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (ui.presentation) "켜짐" else "꺼짐", style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
            Switch(ui.presentation, onCheckedChange = null)
        }
    }
    if (ui.presentation) SettingRow("엔진 화면 열기", "Pleos 신호와 이번 트립 지표를 실시간으로 봐요") {
        PillButton("열기", onDevEngine, kind = PillKind.SECONDARY, enabled = !ui.busy)
    }
    SettingRow("내 데이터 지우기", if (ui.deletionAvailable) "이 차에 모인 운전 지문과 라벨을 모두 지워요" else "데이터 삭제를 준비하고 있어요") {
        PillButton("지우기", { onDeleteDialog(true) }, kind = PillKind.SECONDARY, enabled = !ui.busy && ui.deletionAvailable)
    }
}

@Composable
private fun SettingRow(title: String, detail: String, trailing: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
            trailing()
        }
    }
}

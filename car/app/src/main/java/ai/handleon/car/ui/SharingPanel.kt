package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SharingBar(ui: SharingUi, busy: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(ui.status, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink)
        PillButton(ui.action, onToggle, kind = PillKind.SECONDARY, enabled = !busy)
    }
}

@Composable
fun SharingPanel(ui: SharingUi?, busy: Boolean, error: String?, confirmRevoke: Boolean,
                 onToggle: () -> Unit, onRevokeDialog: (Boolean) -> Unit, onRevoke: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), padding = 24.dp) {
        Text("가족 공유", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        ui?.let {
            it.recipients.forEach { recipient ->
                Text(recipient.description, style = MaterialTheme.typography.bodySmall, color = DarkTokens.ink)
            }
            if (it.recipients.isEmpty()) Text("공유 중인 가족이 없어요", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.status, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                    PillButton(it.action, onToggle, enabled = !busy)
                }
                PillButton("모두 끊기", { onRevokeDialog(true) }, kind = PillKind.SECONDARY,
                    enabled = !busy && it.recipients.isNotEmpty())
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted) }
        Spacer(Modifier.height(12.dp))
        Text(SHARING_PROMISE, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        Text(SHARING_PREVIEW, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
    }
    if (confirmRevoke) AlertDialog(onDismissRequest = { if (!busy) onRevokeDialog(false) },
        title = { Text("모든 가족과의 공유를 끊을까요?") },
        text = { Text("다시 나누려면 휴대폰에서 새로 초대해야 해요.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { PillButton("모두 끊기", onRevoke, kind = PillKind.CORAL, enabled = !busy) },
        dismissButton = { PillButton("돌아가기", { onRevokeDialog(false) }, kind = PillKind.SECONDARY, enabled = !busy) })
}

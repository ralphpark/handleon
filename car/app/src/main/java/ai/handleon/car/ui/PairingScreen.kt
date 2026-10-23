package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PairingScreen(ui: PairingUi, onNewCode: () -> Unit, onSync: () -> Unit) {
    Text("휴대폰 연결", style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when {
            !ui.configured -> PairingText("서버 설정이 없어요")
            ui.error -> {
                PairingText("연결하지 못했어요. 인터넷 연결을 확인해 주세요.")
                PillButton("다시 해 보기", onNewCode, enabled = !ui.busy)
            }
            ui.code != null -> {
                PairingText("휴대폰의 핸들온 앱에서 이 코드를 찍거나 입력하세요.")
                if (ui.remainingSeconds > 0) {
                    BoxWithConstraints {
                        if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            ui.matrix?.let { QrCode(it) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { CodeCopy(ui) }
                        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            ui.matrix?.let { QrCode(it) }
                            CodeCopy(ui)
                        }
                    }
                } else {
                    PairingText("코드가 만료됐어요")
                    PillButton("새 코드 만들기", onNewCode, enabled = !ui.busy)
                }
            }
            ui.driverId != null -> {
                Text("휴대폰과 연결됐어요", style = MaterialTheme.typography.titleMedium, color = DarkTokens.brand)
                PairingText(ui.lastSyncMs?.let { "마지막으로 보낸 때: ${formatSyncTime(it)}" } ?: "아직 보내지 않았어요")
                PillButton("지금 보내기", onSync, enabled = !ui.busy)
                PillButton("다른 휴대폰으로 바꾸기", onNewCode, kind = PillKind.SECONDARY, enabled = !ui.busy)
            }
            else -> {
                PairingText("휴대폰의 핸들온 앱에서 이 코드를 찍거나 입력하세요.")
                PillButton(if (ui.busy) "연결 코드를 만들고 있어요" else "연결 코드 만들기", onNewCode, enabled = !ui.busy)
            }
        }
    }
}

@Composable
private fun CodeCopy(ui: PairingUi) {
    Text(ui.code.orEmpty().chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineLarge, color = DarkTokens.ink)
    PairingText("10분 동안 쓸 수 있어요 · 남은 시간 ${ui.remainingSeconds / 60}:${(ui.remainingSeconds % 60).toString().padStart(2, '0')}")
}

@Composable
private fun PairingText(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }

internal fun formatSyncTime(ms: Long): String = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)
    .format(Instant.ofEpochMilli(ms).atZone(KST))

package ai.handleon.car.ui

import ai.handleon.car.engine.Mode
import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

// Task 8 consent wording, deliberately separate from optional mode selection.
val CONSENTS = listOf("위치는 차 안에서만 처리", "운전 습관에서 건강 관련 변화를 추정하는 것에 대한 별도 동의", "의료기기가 아님을 확인")

@Composable
fun FirstRunScreen(ui: FirstRunUi, today: LocalDate, onChange: (FirstRunUi) -> Unit, onStart: () -> Unit) {
    ScreenFrame { wide ->
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(5f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(36.dp)) { Welcome() }
            FirstRunForm(ui, today, onChange, onStart, Modifier.weight(6f))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { Logo(); Text("핸들온", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink) }
            Text("평소의 운전과 오늘을\n조용히 견주어 드려요", style = MaterialTheme.typography.displaySmall, color = DarkTokens.ink)
            FirstRunForm(ui, today, onChange, onStart, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Welcome() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Logo(); Text("핸들온", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
    }
    WelcomeGauge()
    Text("핸들온에 오신 것을 환영해요", style = MaterialTheme.typography.labelLarge, color = DarkTokens.brand)
    Text("평소의 운전과 오늘을\n조용히 견주어 드려요", style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, lineHeight = 78.sp), color = DarkTokens.ink)
    Text("운전하시는 동안 차가 보내는 신호로 나만의 운전 지문을 만들고, 평소와 달라진 점이 있으면 쉬운 말로 알려 드려요.", style = MaterialTheme.typography.bodyLarge, color = DarkTokens.inkMuted)
}

@Composable
private fun FirstRunForm(ui: FirstRunUi, today: LocalDate, onChange: (FirstRunUi) -> Unit, onStart: () -> Unit, modifier: Modifier) {
    GlassPanel(modifier, 36.dp, contentPadding = PaddingValues(horizontal = 44.dp, vertical = 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("태어난 해", style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
                OutlinedTextField(ui.birthYear, { onChange(ui.withBirthYear(it, today)) },
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("birth-year"), enabled = !ui.starting,
                    textStyle = MaterialTheme.typography.titleLarge, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp), placeholder = { Text("예: 1956", style = MaterialTheme.typography.titleLarge) })
                ModeChoices(ui.mode, { onChange(ui.copy(mode = it)) }, enabled = !ui.starting)
                Text("태어난 해로 골라 드려요. 직접 고르거나 나중에 설정에서 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
            HorizontalDivider(color = DarkTokens.line)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("시작하기 전에 확인해 주세요", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
                CONSENTS.forEachIndexed { i, text ->
                    val checked = ui.consents.getOrElse(i) { false }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("consent-$i")
                        .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                        .midnightClick(!ui.starting, Role.Checkbox) {
                            onChange(ui.copy(consents = ui.consents.mapIndexed { index, value -> if (index == i) !value else value }))
                        }.background(if (checked) DarkTokens.brand.copy(alpha = .08f) else DarkTokens.surfaceRaised, RoundedCornerShape(20.dp))
                        .border(if (checked) 2.dp else 1.dp, if (checked) DarkTokens.brand else DarkTokens.line, RoundedCornerShape(20.dp))
                        .padding(22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).border(2.dp, if (checked) DarkTokens.brand else DarkTokens.inkMuted, RoundedCornerShape(8.dp)), Alignment.Center) {
                            if (checked) Icon(MidnightIcons.Check, null, Modifier.size(26.dp), tint = DarkTokens.brand)
                        }
                        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = DarkTokens.ink)
                    }
                }
            }
            PillButton(if (ui.starting) "시작하는 중이에요" else "시작하기", onStart, Modifier.fillMaxWidth().testTag("start"), enabled = ui.canStart(today))
            Text("세 가지를 모두 확인하면 시작할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
        }
    }
}

@Composable
fun ModeChoices(mode: Mode, onMode: (Mode) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Mode.entries.forEach { item ->
            Column(Modifier.weight(1f).heightIn(min = 108.dp).testTag("mode-${item.name}")
                .semantics { selected = mode == item }.midnightClick(enabled, Role.RadioButton) { onMode(item) }
                .background(if (mode == item) DarkTokens.brand.copy(alpha = .08f) else DarkTokens.surfaceRaised, RoundedCornerShape(20.dp))
                .border(if (mode == item) 2.dp else 1.dp, if (mode == item) DarkTokens.brand else DarkTokens.line, RoundedCornerShape(20.dp))
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (item == Mode.MAEUM) "마음 운전" else "운전 컨디션", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
                Text(if (item == Mode.MAEUM) "60세 이상 · 몇 달에 걸친 변화" else "하루·한 주의 컨디션", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
        }
    }
}

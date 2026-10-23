package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun LabelsScreen(ui: LabelsUi, onDay: (Boolean) -> Unit, onLabel: (String?) -> Unit, onBack: () -> Unit) {
    ScreenFrame { wide ->
        PageHeader("라벨 붙이기", onBack)
        Text("라벨을 붙인 날은 변화 판단에서 빼요.", style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
        SegmentedControl(if (ui.yesterday) 1 else 0, { onDay(it == 1) },
            options = listOf("오늘 · ${ui.today.monthValue}월 ${ui.today.dayOfMonth}일", "어제 · ${ui.today.minusDays(1).monthValue}월 ${ui.today.minusDays(1).dayOfMonth}일"))
        val cells = DayLabel.entries.map { it as DayLabel? } + listOf(null)
        cells.chunked(if (wide) 3 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { label ->
                    val selected = label != null && ui.selected == label.name
                    Column(Modifier.weight(1f).heightIn(min = if (wide) 300.dp else 220.dp)
                        .semantics { this.selected = selected }.midnightClick(!ui.busy, Role.Button) { onLabel(if (selected) null else label?.name) }
                        .background(if (selected) DarkTokens.brand.copy(alpha = .08f) else DarkTokens.surfaceRaised, RoundedCornerShape(28.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) DarkTokens.brand else DarkTokens.line, RoundedCornerShape(28.dp))
                        .padding(36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        val icon = when (label) { DayLabel.SLEEP -> MidnightIcons.Sleep; DayLabel.COLD -> MidnightIcons.Thermometer
                            DayLabel.NEW_MEDICINE -> MidnightIcons.Medicine; DayLabel.TRAVEL -> MidnightIcons.Travel
                            DayLabel.CAR_CHANGED -> MidnightIcons.Car; null -> MidnightIcons.Close }
                        IconTile(icon, if (selected) DarkTokens.brand else DarkTokens.inkMuted)
                        Text(label?.title ?: "라벨 지우기", style = MaterialTheme.typography.headlineMedium, color = DarkTokens.ink)
                        Text(if (selected) "붙였어요" else label?.detail ?: "선택한 날의 라벨을 없애요", style = MaterialTheme.typography.labelMedium,
                            color = if (selected) DarkTokens.brand else DarkTokens.inkMuted)
                    }
                }
            }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("그날의 상황을 기억해 두세요", style = MaterialTheme.typography.titleMedium, color = DarkTokens.brand)
            Text("같은 날에는 라벨 하나를 붙일 수 있어요. 다시 누르면 지워져요.", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted)
        }
    }
}

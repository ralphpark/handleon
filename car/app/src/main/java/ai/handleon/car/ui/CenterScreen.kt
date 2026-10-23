package ai.handleon.car.ui

import ai.handleon.car.centers.Center
import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

data class CenterUi(val centers: List<Pair<Center, Double>> = emptyList(), val loading: Boolean = false,
                    val locationLabel: String = "판교역 기준", val routeMessage: String? = null)

@Composable
fun CenterScreen(ui: CenterUi, onRoute: (Center) -> Unit, onBack: () -> Unit) {
    ScreenFrame(notice = true) { wide ->
        PageHeader("가까운 치매안심센터", onBack)
        Row(Modifier.fillMaxWidth().background(DarkTokens.talkFill.copy(alpha = .12f), RoundedCornerShape(28.dp))
            .border(1.dp, DarkTokens.talkFill.copy(alpha = .25f), RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconTile(MidnightIcons.Heart, DarkTokens.talkFill)
            Text("60세 이상은 무료로 선별검사를 받을 수 있어요.", style = MaterialTheme.typography.headlineSmall, color = DarkTokens.ink)
        }
        if (ui.centers.isEmpty()) GlassPanel(Modifier.fillMaxWidth().heightIn(min = 360.dp), padding = 44.dp) {
            Text(if (ui.loading) "가까운 곳을 찾고 있어요" else "센터 목록을 준비하고 있어요", style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
        } else {
            Text("${ui.locationLabel} · 가까운 3곳", style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
            ui.centers.take(3).forEachIndexed { i, (center, km) ->
                GlassPanel(Modifier.fillMaxWidth(), padding = 28.dp) {
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("${i+1}", style = MaterialTheme.typography.titleLarge, color = DarkTokens.talkFill)
                        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(String.format(Locale.KOREA, "%.1f", km), style = MaterialTheme.typography.headlineLarge, color = DarkTokens.ink)
                            Text("km", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { CenterDetails(center) }
                        PillButton("길안내", { onRoute(center) }, icon = MidnightIcons.Route)
                    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${i+1} · ${String.format(Locale.KOREA, "%.1f", km)} km", style = MaterialTheme.typography.titleMedium, color = DarkTokens.talkFill)
                        CenterDetails(center)
                        PillButton("길안내", { onRoute(center) }, icon = MidnightIcons.Route)
                    }
                }
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                Text("검사 전에 전화로 예약이 필요한지 물어보시면 좋아요. 가족과 함께 가셔도 돼요.", style = MaterialTheme.typography.bodyMedium, color = DarkTokens.ink)
                Text("가까운 곳은 차 안에서만 계산했어요 · 공공데이터포털 전국치매센터표준데이터", style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            }
        }
        ui.routeMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted) }
    }
}

@Composable
private fun CenterDetails(center: Center) {
    Text(center.name, style = MaterialTheme.typography.titleLarge, color = DarkTokens.ink)
    Text(center.address, style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
    Text(center.phone, style = MaterialTheme.typography.labelLarge, color = DarkTokens.inkMuted)
}

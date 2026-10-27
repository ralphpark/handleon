package ai.handleon.car.gleo

import ai.handleon.car.ui.components.GlassPanel
import ai.handleon.car.ui.components.PillButton
import ai.handleon.car.ui.components.PillKind
import ai.handleon.car.ui.theme.DarkTokens
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

fun gleoMockVisible(presentation: Boolean, driving: Boolean) = presentation && !driving

@Composable
fun GleoMockPanel(onOpenConfirmation: () -> Unit) {
    val context = LocalContext.current
    var response by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { GleoWire.mockResponse(context.packageName, intent.getStringExtra("packageName"), intent.getStringExtra("response")) }
                    .getOrNull()?.let { response = it }
            }
        }
        val filter = IntentFilter(GleoWire.RESPONSE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("모의 글레오 · 실제 연동은 Pleos 승인 후", style = MaterialTheme.typography.titleMedium, color = DarkTokens.ink)
        PillButton(if (week) "운전 요약 기간 · 이번 주" else "운전 요약 기간 · 오늘", { week = !week }, kind = PillKind.SECONDARY)
        val labels = listOf("운전 요약", "운전 약속", "비교 준비", "다른 운전자 표시", "가족 공유 멈추기")
        for ((index, function) in GleoActions.FUNCTIONS.withIndex()) {
            PillButton(labels[index], {
                response = null
                context.sendBroadcast(Intent(function).setPackage(context.packageName).putExtra(GleoWire.PARAM,
                    if (function == "GET_DRIVING_SUMMARY") "{\"period\":\"${if (week) "week" else "today"}\"}" else "{}"))
            }, modifier = Modifier.padding(top = 8.dp), kind = PillKind.SECONDARY)
        }
        response?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = DarkTokens.inkMuted)
            PillButton("주차 확인 카드 보기", onOpenConfirmation, kind = PillKind.SECONDARY)
        }
    }
}

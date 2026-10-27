package ai.handleon.car.ui

import ai.handleon.car.ui.components.*
import ai.handleon.car.ui.theme.DarkTokens
import ai.handleon.driveprint.Promise
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun PromiseSettings(selected: Set<Promise>, onChange: (Set<Promise>) -> Unit, onBack: () -> Unit) {
    ScreenFrame {
        PageHeader("운전 약속", onBack)
        Text("내가 지키고 싶은 약속을 골라요", color = DarkTokens.inkMuted, style = MaterialTheme.typography.bodyLarge)
        Promise.entries.forEach { promise ->
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(promiseTitle(promise), Modifier.weight(1f), color = DarkTokens.ink, style = MaterialTheme.typography.titleMedium)
                    Switch(promise in selected, { onChange(if (it) selected + promise else selected - promise) })
                }
            }
        }
    }
}

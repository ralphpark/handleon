package ai.handleon.car.ui

import ai.handleon.car.ui.components.Logo
import ai.handleon.car.ui.components.MidnightBackground
import ai.handleon.car.ui.theme.DarkTokens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DrivingScreen(rest: String? = null) {
    MidnightBackground {
        Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(64.dp)
            Text("핸들온이 함께하고 있어요", style = MaterialTheme.typography.displayLarge, color = DarkTokens.ink, textAlign = TextAlign.Center)
            if (rest != null) {
                Text(REST_DRIVING, style = MaterialTheme.typography.bodyLarge, color = DarkTokens.inkMuted, textAlign = TextAlign.Center)
                Text(rest, style = MaterialTheme.typography.bodyMedium, color = DarkTokens.inkMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

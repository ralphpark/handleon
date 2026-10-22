package ai.handleon.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.handleon.car.ui.theme.HandleOnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HandleOnTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
                    Text("핸들온", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

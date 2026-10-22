package ai.handleon.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import ai.handleon.car.collect.CollectorService
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
        // 속도(CAR_SPEED)·위치는 런타임 퍼미션. 거부돼도 그 신호만 빠지고 수집은 돈다. 첫 실행 화면(T8)이 붙으면 그쪽으로 옮긴다.
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { CollectorService.start(this) }
            .launch(RUNTIME_PERMISSIONS)
        setContent {
            HandleOnTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
                    Text("핸들온", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = arrayOf(
            "android.car.permission.CAR_SPEED",
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

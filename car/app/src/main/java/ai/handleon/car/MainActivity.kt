package ai.handleon.car

import ai.handleon.car.collect.BootReceiver
import ai.handleon.car.collect.CollectorService
import ai.handleon.car.engine.DailyWorker
import ai.handleon.car.engine.Mode
import ai.handleon.car.ui.Nav
import ai.handleon.car.ui.ReplayViewModel
import ai.handleon.car.ui.theme.HandleOnTheme
import android.Manifest
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as HandleOnApp).graph
    private var collectionAllowed by mutableStateOf(false)
    private var driving by mutableStateOf(false)
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var alwaysLocation by mutableStateOf(false)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        finishOnboarding()
    }
    // 안드로이드 11+ 는 '항상 허용'을 따로, 앱 사용 중 위치를 받은 뒤에만 물을 수 있다(시스템이 설정 화면을 연다).
    private val backgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        alwaysLocation = hasBackgroundLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val onboarded = graph.firstRunComplete()
        collectionAllowed = hasLocationPermission()
        alwaysLocation = hasBackgroundLocation()
        enableBootCollection(onboarded && collectionAllowed && !graph.erasingData)
        if (!graph.erasingData) {
            if (onboarded && collectionAllowed) CollectorService.start(this)
            lifecycleScope.launch { runEngine(graph.mode()) }
        }
        connectUxRestrictions()
        val replay = ViewModelProvider(this)[ReplayViewModel::class.java]
        setContent {
            val engine by graph.engineRunner.state.collectAsStateWithLifecycle()
            val onboarded by graph.setupCompleted.collectAsStateWithLifecycle()
            HandleOnTheme {
                Nav(graph, engine, replay, driving, onboarded, collectionAllowed,
                    onStart = { mode -> graph.setMode(mode); permissions.launch(RUNTIME_PERMISSIONS) },
                    onRequestPermissions = { permissions.launch(RUNTIME_PERMISSIONS) },
                    onDataCleared = { enableBootCollection(false) },
                    alwaysLocation = alwaysLocation, onAlwaysLocation = ::requestAlwaysLocation)
            }
        }
    }

    private fun finishOnboarding() {
        collectionAllowed = hasLocationPermission()
        lifecycleScope.launch {
            runEngine(graph.mode())
            graph.setFirstRunComplete(true)
            enableBootCollection(collectionAllowed)
            // A location foreground service cannot start without location permission on Android 14+.
            if (collectionAllowed) CollectorService.start(this@MainActivity)
            DailyWorker.schedule(this@MainActivity, graph.mode())
            // 첫 실행의 마지막 단계: 시동 직후부터 모으려면 '항상 허용'. 거절해도 앱을 열 때부터는 모은다.
            requestAlwaysLocation()
        }
    }

    private fun requestAlwaysLocation() {
        if (hasLocationPermission() && !hasBackgroundLocation()) backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun onResume() {
        super.onResume()
        // 설정 앱에서 바꾸고 돌아온 경우
        alwaysLocation = hasBackgroundLocation()
    }

    private fun hasBackgroundLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun runEngine(mode: Mode) {
        try { graph.engineRunner.run(mode) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Log.w("HandleOnUi", "기록 갱신 실패", e) }
    }

    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableBootCollection(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(ComponentName(this, BootReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    private fun connectUxRestrictions() {
        try {
            car = Car.createCar(this, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!ready) { ux = null; driving = false; return@createCar }
                try {
                    val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux = manager
                    manager.registerListener { restriction -> runOnUiThread { driving = restriction.isRequiresDistractionOptimization } }
                    driving = manager.currentCarUxRestrictions.isRequiresDistractionOptimization
                } catch (e: Exception) { driving = false; Log.w("HandleOnUi", "차량 화면 제한 서비스에 연결하지 못했어요", e) }
            }
        } catch (e: Exception) { driving = false; Log.w("HandleOnUi", "차량 서비스에 연결하지 못했어요", e) }
    }

    override fun onDestroy() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        ux = null; car = null
        super.onDestroy()
    }

    private companion object {
        val RUNTIME_PERMISSIONS = arrayOf(
            "android.car.permission.CAR_SPEED",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

package ai.handleon.car.signals

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** AAOS CarPropertyManager: 속도(m/s → km/h)와 기어. Pleos SDK 에는 속도·기어가 없다(실측). 퍼미션 CAR_SPEED·CAR_POWERTRAIN. */
class CarSignals(private val context: Context, private val sink: LatestSignals) {
    private var car: Car? = null
    var failed: String? = null
        private set

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            when (v.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v.value as? Float)?.let { sink.set(Key.SPEED, it * 3.6) }
                VehiclePropertyIds.GEAR_SELECTION -> (v.value as? Int)?.let { sink.set(Key.GEAR, gearFromCar(it)) }
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(VehicleSignals.TAG, "CarProperty 오류 ${VehiclePropertyIds.toString(propertyId)}")
        }
    }

    fun start() {
        // 시동 직후(BOOT_COMPLETED)에는 차량 서비스가 아직 없을 수 있다. 준비됐을 때, 그리고 서비스가 다시 떴을 때 구독한다.
        runCatching {
            car = Car.createCar(context.applicationContext, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!ready) { failed = "속도·기어"; return@createCar }
                runCatching {
                    val cpm = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    // 속도는 연속 값이라 1초 주기로 받는다(값이 그대로여도 들어와야 오래된 값과 구분된다). 기어는 바뀔 때만.
                    cpm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
                    cpm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    failed = null
                }.onFailure { subscribeFailed(it) }
            }
        }.onFailure { subscribeFailed(it) }
    }

    private fun subscribeFailed(e: Throwable) {
        failed = "속도·기어"
        Log.w(VehicleSignals.TAG, "CarProperty 구독 실패: $e")
    }

    fun stop() {
        runCatching { (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(callback) }
        car?.disconnect()
        car = null
    }
}

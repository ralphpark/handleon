package ai.handleon.car.signals

import ai.handleon.driveprint.TelltaleOn
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import ai.pleos.playground.vehicle.listener.SeatHeightPositionListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Vehicle SDK 구독: 조향·브레이크·방향지시등·앞 와이퍼·경고등.
 * register* 는 퍼미션이 없으면 onFailed 가 아니라 SecurityException 을 바로 던진다(실측) → 신호마다 감싸서 그 신호만 빠지게 한다.
 */
class VehicleSignals(context: Context, private val sink: LatestSignals) {
    private val vehicle = Vehicle(context.applicationContext)
    private val errorsFlow = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = errorsFlow.asStateFlow()
    private var telltalesOn = emptySet<String>()

    fun start() {
        // initialize() 는 완료 콜백이 없다. SDK 가 연결될 때까지 요청을 쌓아 둔다(실측).
        vehicle.initialize()
        val wheel = vehicle.getSteeringWheel()
        guard("조향각") {
            wheel.getCurrentSteeringWheelAngle({ sink.set(Key.STEERING, it?.toDouble()) }, { fail("조향각", it) })
            wheel.registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = sink.set(Key.STEERING, angle?.toDouble())
                override fun onFailed(e: Exception) = fail("조향각", e)
            })
        }
        val brake = vehicle.getBrake()
        guard("브레이크") {
            brake.getCurrentBrakePedalStatus({ sink.set(Key.BRAKE, it) }, { fail("브레이크", it) })
            brake.registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = sink.set(Key.BRAKE, status)
                override fun onFailed(e: Exception) = fail("브레이크", e)
            })
        }
        val turn = vehicle.getTurnSignal()
        guard("방향지시등") {
            turn.getCurrentTurnSignalState({ sink.set(Key.SIGNAL, it.toSignal()) }, { fail("방향지시등", it) })
            turn.registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = sink.set(Key.SIGNAL, state.toSignal())
                override fun onFailed(e: Exception) = fail("방향지시등", e)
            })
        }
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = vehicle.getWiper()
        guard("와이퍼") {
            wiper.getCurrentWiperState(front, { sink.set(Key.WIPER, it.toWiperOn()) }, { fail("와이퍼", it) })
            wiper.registerWiperState(front, object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) = sink.set(Key.WIPER, state.toWiperOn())
                override fun onFailed(e: Exception) = fail("와이퍼", e)
            })
        }
        val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
        guard("시트 앞뒤") {
            val seat = vehicle.getSeat()
            seat.getCurrentSeatForeAftPosition(driverSeat, { sink.set(Key.SEAT_FORE_AFT, it) }, { sink.set(Key.SEAT_FORE_AFT, null); fail("시트 앞뒤", it) })
            seat.registerSeatForeAftPosition(driverSeat, object : SeatForeAftPositionListener {
                override fun onSeatForeAftPositionUpdated(position: Int?) = sink.set(Key.SEAT_FORE_AFT, position)
                override fun onFailed(e: Exception) { sink.set(Key.SEAT_FORE_AFT, null); fail("시트 앞뒤", e) }
            })
        }
        guard("시트 높이") {
            val seat = vehicle.getSeat()
            seat.getCurrentSeatHeightPosition(driverSeat, { sink.set(Key.SEAT_HEIGHT, it) }, { sink.set(Key.SEAT_HEIGHT, null); fail("시트 높이", it) })
            seat.registerSeatHeightPosition(driverSeat, object : SeatHeightPositionListener {
                override fun onSeatHeightPositionUpdated(position: Int?) = sink.set(Key.SEAT_HEIGHT, position)
                override fun onFailed(e: Exception) { sink.set(Key.SEAT_HEIGHT, null); fail("시트 높이", e) }
            })
        }
        guard("시트 등받이") {
            val seat = vehicle.getSeat()
            seat.getCurrentSeatBackrestAnglePosition(driverSeat, { sink.set(Key.SEAT_BACKREST, it) }, { sink.set(Key.SEAT_BACKREST, null); fail("시트 등받이", it) })
            seat.registerSeatBackrestAnglePosition(driverSeat, object : SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(position: Int?) = sink.set(Key.SEAT_BACKREST, position)
                override fun onFailed(e: Exception) { sink.set(Key.SEAT_BACKREST, null); fail("시트 등받이", e) }
            })
        }
        val safety = vehicle.getSafety()
        guard("경고등") {
            safety.getTelltalesInfo({ onTelltales(it) }, { fail("경고등", it) })
            safety.registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(telltales: Telltales?) = onTelltales(telltales)
                override fun onFailed(e: Exception) = fail("경고등", e)
            })
        }
    }

    fun stop() = runCatching { vehicle.release() }.let { }

    /** 콜백마다 켜진 경고등 목록이 통째로 온다 → 꺼짐→켜짐으로 바뀐 것만 한 건으로 넘긴다(리뷰 #17). */
    @Synchronized private fun onTelltales(t: Telltales?) {
        val on = t?.telltales.orEmpty().filter { it.state != TelltaleState.OFF }.map { it.type.name }.toSet()
        val now = sink.now()
        (on - telltalesOn).forEach { sink.onTelltale(TelltaleOn(now, it)) }
        telltalesOn = on
    }

    private fun guard(name: String, block: () -> Unit) {
        runCatching(block).onFailure { fail(name, it) }
    }

    private fun fail(name: String, e: Throwable) {
        errorsFlow.update { if (name in it) it else it + name }
        android.util.Log.w(TAG, "$name 신호 실패: ${e.javaClass.simpleName} ${e.message}")
    }

    companion object { const val TAG = "HandleOnSignals" }
}

package ai.handleon.car.signals

import ai.handleon.driveprint.NaviEvent
import ai.handleon.driveprint.TelltaleOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Key { SPEED, GEAR, BRAKE, STEERING, SIGNAL, WIPER, POS, HEADING, LIMIT, SEAT_FORE_AFT, SEAT_HEIGHT, SEAT_BACKREST }

data class Stamped(val value: Any?, val atMs: Long)

typealias Snapshot = Map<Key, Stamped>

/** 신호마다 최신값과 받은 시각. 여러 SDK 콜백 스레드에서 쓰고 1초 틱이 읽는다. */
class LatestSignals(private val clock: () -> Long = System::currentTimeMillis) {
    private val values = HashMap<Key, Stamped>()
    private val navi = ArrayList<NaviEvent>()
    private val telltales = ArrayList<TelltaleOn>()
    private val readyFlow = MutableStateFlow(false)

    /** 차량 신호(속도·기어·브레이크·조향·지시등·와이퍼)가 처음 하나 들어오면 true. 그 전에는 "차량 신호 연결 중". */
    val ready: StateFlow<Boolean> = readyFlow.asStateFlow()

    @Synchronized fun set(key: Key, value: Any?) {
        values[key] = Stamped(value, clock())
        if (value != null && key in VEHICLE_KEYS) readyFlow.value = true
    }

    @Synchronized fun snapshot(): Snapshot = HashMap(values)

    @Synchronized fun onNavi(e: NaviEvent) { navi.add(e) }

    @Synchronized fun onTelltale(e: TelltaleOn) { telltales.add(e) }

    @Synchronized fun drainEvents(): Pair<List<NaviEvent>, List<TelltaleOn>> {
        val out = navi.toList() to telltales.toList()
        navi.clear(); telltales.clear()
        return out
    }

    fun now(): Long = clock()

    private companion object {
        val VEHICLE_KEYS = setOf(Key.SPEED, Key.GEAR, Key.BRAKE, Key.STEERING, Key.SIGNAL, Key.WIPER)
    }
}

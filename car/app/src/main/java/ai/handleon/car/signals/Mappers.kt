package ai.handleon.car.signals

import ai.handleon.driveprint.Gear
import ai.handleon.driveprint.LatLon
import ai.handleon.driveprint.Signal
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// SDK 값을 엔진 값으로 바꾸는 순수 함수. 실측은 spikes/sdk-probe/RESULTS.md

fun TurnSignalValue?.toSignal(): Signal? = when (this) {
    TurnSignalValue.OFF -> Signal.OFF
    TurnSignalValue.LEFT -> Signal.LEFT
    TurnSignalValue.RIGHT -> Signal.RIGHT
    null -> null
}

/** 와이퍼는 ON/OFF 만 믿는다. SERVICE·OTHER 는 모름. */
fun WiperState?.toWiperOn(): Boolean? = when (this) {
    WiperState.ON -> true
    WiperState.OFF -> false
    else -> null
}

/** 내비 회전 안내의 방향. 직진·IC·지하차도 진입 등은 지시등 판정에 쓰지 않는다. */
fun TurnType.toSignal(): Signal? = when (this) {
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_KEEP_L, TurnType.TURN_UTURN -> Signal.LEFT
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R -> Signal.RIGHT
    else -> null
}

/** AAOS GEAR_SELECTION (VehicleGear: NEUTRAL 1, REVERSE 2, PARK 4, DRIVE 8). */
fun gearFromCar(value: Int): Gear = when (value) {
    4 -> Gear.P
    8 -> Gear.D
    2 -> Gear.R
    1 -> Gear.N
    else -> Gear.UNKNOWN
}

/** 두 점의 방위각(북 0°, 시계 방향). GPS bearing 이 0 으로만 와서 연속 위치로 계산한다(실측). */
fun bearingDeg(a: LatLon, b: LatLon): Double {
    val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

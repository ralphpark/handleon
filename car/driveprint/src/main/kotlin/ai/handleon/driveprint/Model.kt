package ai.handleon.driveprint

import kotlinx.serialization.Serializable

enum class Gear { P, R, N, D, UNKNOWN }
enum class Signal { OFF, LEFT, RIGHT, HAZARD }

@Serializable
data class LatLon(val lat: Double, val lon: Double)

data class Sample(
    val tMs: Long,
    val speedKmh: Double?,
    val gear: Gear,
    val brake: Boolean?,
    val steeringDeg: Double?,
    val signal: Signal?,
    val wiperOn: Boolean?,
    val pos: LatLon?,
    val headingDeg: Double?,
    val speedLimitKmh: Int?,
)

sealed interface NaviEvent { val tMs: Long }
data class GuidanceStarted(override val tMs: Long, val dest: LatLon, val name: String) : NaviEvent
data class GuidanceEnded(override val tMs: Long) : NaviEvent
data class Reroute(override val tMs: Long) : NaviEvent
data class TurnAhead(override val tMs: Long, val direction: Signal, val distanceM: Double) : NaviEvent
data class TurnPassed(override val tMs: Long, val direction: Signal) : NaviEvent
data class TelltaleOn(val tMs: Long, val type: String)
data class RawTrip(val samples: List<Sample>, val navi: List<NaviEvent>, val telltales: List<TelltaleOn>)

@Serializable
enum class Source { CAR, TIMELINE, SIM }

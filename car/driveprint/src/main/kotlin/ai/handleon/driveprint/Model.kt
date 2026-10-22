package ai.handleon.driveprint

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
enum class Gear { P, R, N, D, UNKNOWN }
@Serializable
enum class Signal { OFF, LEFT, RIGHT, HAZARD }

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
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

@Serializable
sealed interface NaviEvent { val tMs: Long }
@Serializable
data class GuidanceStarted(override val tMs: Long, val dest: LatLon, val name: String) : NaviEvent
@Serializable
data class GuidanceEnded(override val tMs: Long) : NaviEvent
@Serializable
data class Reroute(override val tMs: Long) : NaviEvent
@Serializable
data class TurnAhead(override val tMs: Long, val direction: Signal, val distanceM: Double) : NaviEvent
@Serializable
data class TurnPassed(override val tMs: Long, val direction: Signal) : NaviEvent
@Serializable
data class TelltaleOn(val tMs: Long, val type: String)
@Serializable
data class RawTrip(val samples: List<Sample>, val navi: List<NaviEvent>, val telltales: List<TelltaleOn>)

@Serializable
enum class Source { CAR, TIMELINE, SIM }

@Serializable enum class ResetKind { HOME_CHANGED, CAR_CHANGED, ALL }
@Serializable
data class Reset(@Serializable(with = LocalDateSerializer::class) val day: LocalDate, val kind: ResetKind)

package ai.handleon.driveprint

import kotlinx.serialization.Serializable

@Serializable
data class TripFeatures(
    val startMs: Long,
    val endMs: Long,
    val source: Source,
    val distanceKm: Double,
    val night: Boolean,
    val rain: Boolean,
    val offDay: Boolean,
    val endCell: Long?,
    val maxFromHomeKm: Double?,
    val familiarDest: Boolean,
    val guided: Boolean,
    val guidedKm: Double,
    val reroutes: Int,
    val leftTurns: Int,
    val rightTurns: Int,
    val announcedTurns: Int,
    val signalMisses: Int,
    val speedingRatio: Double?,
    val hardBrakes: Int,
    val steeringStd: Double?,
    val steeringReversalsPerMin: Double?,
    val lkaEvents: Int,
    val fcaEvents: Int,
)

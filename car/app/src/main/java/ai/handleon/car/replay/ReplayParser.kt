package ai.handleon.car.replay

import ai.handleon.driveprint.TripFeatures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReplayData(val version: Int, val persona: String, val trips: List<TripFeatures>)

object ReplayParser {
    fun parse(json: String): ReplayData {
        val replay = Json.decodeFromString<ReplayData>(json)
        require(replay.version == 1) { "지원하지 않는 재생 기록 형식입니다" }
        return replay
    }
}

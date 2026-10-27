package ai.handleon.car.sync

import ai.handleon.driveprint.Outing
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

object DriveNotices {
    const val MAX_AGE_MS = 6 * 60 * 60_000L
    private val key = Regex("[0-9]{10,16}")
    fun toReport(outings: List<Outing>, nowMs: Long): List<Outing> = outings.filter {
        it.endMs >= nowMs - MAX_AGE_MS && it.endMs <= nowMs + 5 * 60_000 && key.matches(it.key)
    }.sortedBy { it.endMs }
    fun reportBody(o: Outing): JsonObject = buildJsonObject {
        require(key.matches(o.key))
        put("p_outing_key", o.key)
        put("p_ended_at", Instant.ofEpochMilli(o.endMs).toString())
        put("p_minutes", (o.driveMs / 60_000).coerceIn(1, 1440).toInt())
        put("p_rested", o.rested)
    }
    fun sharingLine(recipientsWithDriveDone: Int): String? =
        if (recipientsWithDriveDone <= 0) null else "운전 마침 소식을 ${recipientsWithDriveDone}명에게 보내고 있어요"
}

/** Saved before network I/O: even a lost acknowledgement can later be retracted. No coordinates. */
@Serializable
internal data class DriveNoticeLedger(val attempted: Map<String, Outing> = emptyMap(), val retracted: Set<String> = emptySet(),
                                     val replaced: Set<String> = emptySet())

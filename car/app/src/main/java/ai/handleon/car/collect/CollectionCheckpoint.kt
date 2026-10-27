package ai.handleon.car.collect

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CollectionCheckpoint(val segmenter: String, val driver: DriverCapture, val lastSampleMs: Long?,
                                       val otherRequestId: String? = null)

// A parked user can queue another driver after an older checkpoint. Never reuse that old OWNER capture.
internal fun CollectionCheckpoint.needsNewDeparture(request: String?): Boolean =
    driver.captured && request != null && otherRequestId != request

/** Persist departure identity before acknowledging the one-shot request; either side can be retried. */
internal fun saveCollectionCheckpoint(directory: File, saved: CollectionCheckpoint, consumeOther: (String) -> Unit) {
    val temporary = File(directory, "segmenter.tmp")
    FileOutputStream(temporary).use { output ->
        output.write(Json.encodeToString(saved).toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    check(temporary.renameTo(File(directory, "segmenter.json"))) { "Checkpoint replacement failed" }
    saved.otherRequestId?.let(consumeOther)
}

fun clearCollectorCheckpoints(directory: File) {
    for (name in listOf("segmenter.json", "segmenter.tmp")) {
        val file = File(directory, name)
        check(!file.exists() || file.delete()) { "Checkpoint deletion failed" }
    }
}

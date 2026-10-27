package ai.handleon.car.collect

import ai.handleon.car.sync.MemoryPreferences
import ai.handleon.driveprint.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

class CollectionCheckpointTest {
    private val start = 1793088000000L
    private fun captured(request: String): CollectionCheckpoint {
        val segmenter = TripSegmenter()
        segmenter.push(Sample(start, 30.0, Gear.D, null, null, null, null, null, null, null))
        return CollectionCheckpoint(segmenter.snapshot(), DriverCapture(captured = true, other = true), start, request)
    }
    @Test fun `other request is consumed only after departure checkpoint is durable`() {
        val directory = Files.createTempDirectory("handleon-capture").toFile()
        try {
            val prefs = MemoryPreferences()
            val flag = OtherDriverFlag(prefs)
            flag.set(true)
            val request = flag.requestId()!!
            saveCollectionCheckpoint(directory, captured(request), flag::consume)
            assertNull(OtherDriverFlag(prefs).requestId())
            val restored = Json.decodeFromString<CollectionCheckpoint>(File(directory, "segmenter.json").readText())
            assertTrue(restored.driver.other)
            assertEquals(start, restored.lastSampleMs)
            assertEquals(request, restored.otherRequestId)
            val segmenter = TripSegmenter().also { it.restore(restored.segmenter) }
            assertEquals(start, segmenter.current()!!.samples.first().tMs)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `failed checkpoint keeps the one shot request for restart`() {
        val directory = Files.createTempDirectory("handleon-capture").toFile()
        try {
            File(directory, "segmenter.tmp").mkdir()
            val prefs = MemoryPreferences()
            val flag = OtherDriverFlag(prefs)
            flag.set(true)
            val request = flag.requestId()!!
            assertFailsWith<IOException> { saveCollectionCheckpoint(directory, captured(request), flag::consume) }
            assertEquals(request, OtherDriverFlag(prefs).requestId())
            assertFalse(File(directory, "segmenter.json").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `unacknowledged request starts a new departure instead of inheriting an old owner capture`() {
        val previous = captured("old").copy(driver = DriverCapture(captured = true), otherRequestId = null)
        assertTrue(previous.needsNewDeparture("new"))
        assertFalse(previous.needsNewDeparture(null))
        assertFalse(captured("same").needsNewDeparture("same"))
    }
    @Test fun `restart between checkpoint and consumption acknowledges only the saved request`() {
        val directory = Files.createTempDirectory("handleon-capture").toFile()
        try {
            val prefs = MemoryPreferences()
            val flag = OtherDriverFlag(prefs)
            flag.set(true)
            val request = flag.requestId()!!
            assertFailsWith<IOException> {
                saveCollectionCheckpoint(directory, captured(request)) { throw IOException("interrupted before consumption") }
            }
            val saved = Json.decodeFromString<CollectionCheckpoint>(File(directory, "segmenter.json").readText())
            val restarted = OtherDriverFlag(prefs)
            restarted.consume(saved.otherRequestId!!)
            assertNull(restarted.requestId())
            restarted.set(true)
            val newer = restarted.requestId()
            restarted.consume(saved.otherRequestId)
            assertEquals(newer, restarted.requestId())
            assertNotEquals(request, newer)
        } finally { directory.deleteRecursively() }
    }
}

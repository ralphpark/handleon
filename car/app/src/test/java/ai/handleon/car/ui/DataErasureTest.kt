package ai.handleon.car.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*
import ai.handleon.car.collect.clearCollectorCheckpoints

@OptIn(ExperimentalCoroutinesApi::class)
class DataErasureTest {
    @Test fun `erase removes interrupted checkpoint writes as well as the active checkpoint`() {
        val directory = java.nio.file.Files.createTempDirectory("handleon-erase").toFile()
        try {
            java.io.File(directory, "segmenter.json").writeText("saved trip")
            java.io.File(directory, "segmenter.tmp").writeText("interrupted trip")
            java.io.File(directory, "unrelated").writeText("keep")
            clearCollectorCheckpoints(directory)
            assertFalse(java.io.File(directory, "segmenter.json").exists())
            assertFalse(java.io.File(directory, "segmenter.tmp").exists())
            assertEquals("keep", java.io.File(directory, "unrelated").readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `checkpoint deletion failure does not clear tables or mark erase finished`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("handleon-erase").toFile()
        try {
            java.io.File(directory, "segmenter.tmp").mkdir()
            java.io.File(directory, "segmenter.tmp/child").writeText("blocks file deletion")
            var tablesCleared = false; var finished = false
            assertFailsWith<IllegalStateException> {
                eraseCollectedData({}, { clearCollectorCheckpoints(directory) }, { tablesCleared = true }, { finished = true })
            }
            assertFalse(tablesCleared); assertFalse(finished)
        } finally { directory.deleteRecursively() }
    }
    @Test fun eraseWaitsForCollectorAcknowledgement() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val task = launch {
            eraseCollectedData(
                stopAndAwait = { calls += "stop"; stopped.await(); calls += "stopped" },
                clearCheckpoint = { calls += "checkpoint" }, clearTables = { calls += "tables" }, finish = { calls += "preferences" })
        }
        runCurrent()
        assertEquals(listOf("stop"), calls)
        stopped.complete(Unit)
        task.join()
        assertEquals(listOf("stop", "stopped", "checkpoint", "tables", "preferences"), calls)
    }

    @Test fun failedStopDoesNotDeleteAnything() = runTest {
        val calls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            eraseCollectedData(stopAndAwait = { error("no acknowledgement") },
                clearCheckpoint = { calls += "checkpoint" }, clearTables = { calls += "tables" }, finish = { calls += "preferences" })
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun acceptedEraseFinishesAcrossUiCancellation() = runTest {
        val stopped = CompletableDeferred<Unit>()
        var cleared = false
        var finished = false
        val task = launch {
            eraseCollectedData(stopAndAwait = { stopped.await() }, clearCheckpoint = {}, clearTables = { cleared = true }, finish = { finished = true })
        }
        runCurrent()
        task.cancel()
        stopped.complete(Unit)
        task.join()
        assertTrue(cleared)
        assertTrue(finished)
    }
}

package ai.handleon.car.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DataErasureTest {
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

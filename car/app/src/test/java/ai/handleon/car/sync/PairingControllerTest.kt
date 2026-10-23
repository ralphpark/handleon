package ai.handleon.car.sync

import ai.handleon.car.ui.PairingController
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.*

class PairingControllerTest {
    private val server = MockWebServer()
    private val state = SyncState(MemoryPreferences())
    private var now = 0L
    private var enqueued = 0
    private fun controller(enabled: Boolean = true) = PairingController(enabled, Pairing(server.client()), state, { enqueued++ }) { now }
    @After fun close() = server.shutdown()

    @Test fun newLinkPublishesDriverAndEnqueuesOnce() = runTest {
        server.reply("[]"); server.reply("\"ABC123\"")
        val owner = controller()
        owner.newCode()
        assertEquals(600, owner.state.value.remainingSeconds)
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("""[{"driver_id":"driver"}]""")
        owner.poll(); owner.poll()
        assertEquals("driver", state.driverId)
        assertNull(owner.state.value.code)
        assertEquals(1, enqueued)
    }

    @Test fun replacingPhoneKeepsCodeUntilDriverChanges() = runTest {
        state.setDriver("old")
        server.reply("""[{"driver_id":"old"}]"""); server.reply("\"ABC123\"")
        val owner = controller()
        owner.newCode()
        server.reply("""[{"driver_id":"old"}]"""); owner.poll()
        assertEquals("ABC123", owner.state.value.code)
        assertEquals(0, enqueued)
        server.reply("""[{"driver_id":"new"}]"""); owner.poll()
        assertNull(owner.state.value.code)
        assertEquals("new", state.driverId)
        assertEquals(1, enqueued)
    }

    @Test fun countdownExpiresAndDisabledControllerNeverCallsServer() = runTest {
        val disabled = controller(false)
        disabled.newCode(); disabled.poll()
        assertEquals(0, server.requestCount)
        server.reply("[]"); server.reply("\"ABC123\"")
        val owner = controller()
        owner.newCode()
        now = 600_000
        owner.tick()
        assertEquals(0, owner.state.value.remainingSeconds)
    }

    @Test fun pollIsQuietWithoutCodeAndRecoversFromNetworkError() = runTest {
        state.setDriver("driver")
        val owner = controller()
        owner.poll()
        assertEquals(0, server.requestCount) // 연결 해제는 동기화(CarSync)가 확인한다
        server.reply("""[{"driver_id":"driver"}]"""); server.reply("\"ABC123\"")
        owner.newCode()
        server.reply("{}", 500); owner.poll()
        assertTrue(owner.state.value.error)
        assertEquals("driver", state.driverId)
        server.reply("""[{"driver_id":"driver"}]"""); owner.poll()
        assertFalse(owner.state.value.error)
    }
}

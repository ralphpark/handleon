package ai.handleon.car.ui

import ai.handleon.car.replay.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayViewModelTest {
    private fun load(p: ReplayPersona) = ReplayParser.parse(File("src/main/assets/${p.asset}").readText())

    @Test fun `switching persona cancels old loading and uploads only the selected final frame`() = runTest {
        val vm = ReplayViewModel(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<ReplayData>()
        val uploaded = mutableListOf<ReplayPersona>()
        vm.setVisible(true)
        vm.start(ReplayPersona.FATHER, load = { gate.await() }, upload = { f, allowed ->
            if (allowed()) uploaded += f.persona
            true
        })
        runCurrent()
        vm.start(ReplayPersona.NEW_CAR, load = ::load, upload = { f, allowed ->
            assertTrue(allowed()); uploaded += f.persona; true
        })
        gate.complete(load(ReplayPersona.FATHER))
        advanceUntilIdle()
        assertEquals(ReplayPersona.NEW_CAR, vm.state.value.persona)
        assertEquals(13, vm.state.value.frame)
        assertTrue(vm.state.value.complete)
        assertEquals(listOf(ReplayPersona.NEW_CAR), uploaded)
        vm.end()
    }
    @Test fun `hidden or paused replay does not advance and ending does not upload`() = runTest {
        val vm = ReplayViewModel(StandardTestDispatcher(testScheduler))
        var uploads = 0
        vm.start(ReplayPersona.NEW_CAR, load = ::load, upload = { _, _ -> uploads++; true })
        advanceUntilIdle()
        assertEquals(0, vm.state.value.frame)
        vm.setVisible(true); runCurrent()
        assertEquals(1, vm.state.value.frame)
        vm.pause(); advanceUntilIdle()
        assertEquals(1, vm.state.value.frame)
        vm.end(); advanceUntilIdle()
        assertEquals(0, uploads)
    }
    @Test fun `failed replay upload keeps the final scene and shows a fixed message`() = runTest {
        val vm = ReplayViewModel(StandardTestDispatcher(testScheduler))
        vm.setVisible(true)
        vm.start(ReplayPersona.NEW_CAR, load = ::load, upload = { _, _ -> error("private network details") })
        advanceUntilIdle()
        assertTrue(vm.state.value.complete)
        assertEquals("재생은 끝났어요. 결과는 보내지 못했어요.", vm.state.value.uploadMessage)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.uploadMessage!!.contains("private"))
        vm.end()
    }
    @Test fun `leaving during final upload does not leave a permanent checking message`() = runTest {
        val vm = ReplayViewModel(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        vm.setVisible(true)
        vm.start(ReplayPersona.NEW_CAR, load = ::load, upload = { _, allowed ->
            release.await(); assertFalse(allowed()); false
        })
        advanceUntilIdle()
        assertTrue(vm.state.value.complete)
        vm.setVisible(false); release.complete(Unit); advanceUntilIdle()
        vm.setVisible(true); runCurrent()
        assertEquals("재생을 마쳤어요", vm.state.value.uploadMessage)
        vm.end()
    }
}

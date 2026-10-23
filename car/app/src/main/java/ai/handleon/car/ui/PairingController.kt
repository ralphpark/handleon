package ai.handleon.car.ui

import ai.handleon.car.sync.Pairing
import ai.handleon.car.sync.SyncState
import ai.handleon.car.sync.qrMatrix
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PairingUi(
    val configured: Boolean = false, val driverId: String? = null, val lastSyncMs: Long? = null,
    val code: String? = null, val matrix: BitMatrix? = null, val remainingSeconds: Int = 0,
    val busy: Boolean = false, val error: Boolean = false,
)

/** Owns network/timer state outside the stateless settings panel. The route drives poll/tick. */
class PairingController(
    configured: Boolean,
    private val pairing: Pairing,
    private val syncState: SyncState,
    private val onLinked: () -> Unit,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutable = MutableStateFlow(PairingUi(configured = configured))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var expiresMs = 0L
    private var previousDriver: String? = null

    suspend fun newCode() = mutex.withLock {
        if (!mutable.value.configured) return@withLock
        mutable.update { it.copy(busy = true, error = false) }
        try {
            previousDriver = pairing.linkedDriver()
            syncState.setDriver(previousDriver)
            // Start locally before the RPC so a slow reply never extends server validity.
            val started = elapsedMs()
            val code = pairing.newCode()
            expiresMs = started + 600_000
            mutable.update { it.copy(code = code, matrix = qrMatrix(code), error = false) }
            tick()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = true) } }
        finally { mutable.update { it.copy(busy = false) } }
    }

    fun tick() {
        mutable.update { it.copy(remainingSeconds = ((expiresMs - elapsedMs()).coerceAtLeast(0) / 1000).toInt()) }
    }

    suspend fun poll() {
        // 코드가 떠 있을 때만 묻는다: 설정 화면을 열어 둔 것만으로 서버를 두드리지 않는다.
        if (!mutable.value.configured || mutable.value.code == null || !mutex.tryLock()) return
        try {
            val driver = pairing.linkedDriver()
            val changed = driver != syncState.driverId
            syncState.setDriver(driver)
            val claimed = driver != null && (mutable.value.code == null || driver != previousDriver)
            mutable.update { if (claimed) it.copy(code = null, matrix = null, error = false) else it.copy(error = false) }
            if (driver != null && changed) onLinked()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = true) } }
        finally { mutex.unlock() }
    }
}

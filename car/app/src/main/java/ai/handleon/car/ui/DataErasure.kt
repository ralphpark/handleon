package ai.handleon.car.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// 수집 서비스가 멈추고 마지막 저장까지 끝난 뒤(CollectorService.isRunning == false)에만 지운다.
const val COLLECTOR_ERASURE_AVAILABLE = true

suspend fun eraseCollectedData(
    stopAndAwait: suspend () -> Unit,
    clearCheckpoint: suspend () -> Unit,
    clearTables: suspend () -> Unit,
    finish: suspend () -> Unit,
) = withContext(NonCancellable) {
    stopAndAwait()
    clearCheckpoint()
    clearTables()
    finish()
}

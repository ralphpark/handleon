package ai.handleon.car.sync

import ai.handleon.car.HandleOnApp
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val graph = (applicationContext as HandleOnApp).graph
        if (!graph.erasingData) graph.carSync.syncOnce()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount >= 3) Result.failure() else Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("handleon-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}

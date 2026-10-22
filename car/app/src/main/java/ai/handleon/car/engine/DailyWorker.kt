package ai.handleon.car.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.handleon.car.HandleOnApp
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class DailyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val modeName = inputData.getString(KEY_MODE)
        val mode = Mode.entries.firstOrNull { it.name == modeName } ?: return Result.failure()
        return try {
            (applicationContext as HandleOnApp).graph.engineRunner.run(mode)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "handleon-daily-report"
        const val KEY_MODE = "mode"

        /** 모드 선택/변경 뒤 호출. 04:00 KST부터 24시간 간격이며 OS에 따라 실행이 늦어질 수 있다. */
        fun schedule(context: Context, mode: Mode, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))) {
            val seoul = now.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
            val todayAtFour = seoul.toLocalDate().atTime(4, 0).atZone(ZoneId.of("Asia/Seoul"))
            val next = if (todayAtFour.isBefore(seoul)) todayAtFour.plusDays(1) else todayAtFour
            val request = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(seoul, next).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MODE to mode.name))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

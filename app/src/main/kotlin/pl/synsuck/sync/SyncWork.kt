package pl.synsuck.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import pl.synsuck.SynsuckApp
import pl.synsuck.data.SyncInterval
import java.util.concurrent.TimeUnit

/** Periodic background check: message lists only (never opens messages), then notifications. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SynsuckApp).container
        val outcomes = container.syncer.syncAll()
        if (container.settings.notificationsEnabled.first()) {
            container.notifier.notifyOutcomes(outcomes)
        }
        // Network problems just wait for the next period; no aggressive retries against Librus.
        return Result.success()
    }
}

class SyncScheduler(private val context: Context) {
    fun apply(interval: SyncInterval) {
        val workManager = WorkManager.getInstance(context)
        val minutes = interval.minutes
        if (minutes == null) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "sync"
    }
}

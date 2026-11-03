package ph.toms.conductor.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ph.toms.conductor.settings.DeviceConfigStore
import java.util.concurrent.TimeUnit

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val flusher: OutboxFlusher,
    private val configRefresher: ConfigRefresher,
    private val deviceConfig: DeviceConfigStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // No server address yet: nothing is tried, so waiting events are not marked as failed attempts, and a retry
        // later is pointless until the owner sets the address on More.
        if (deviceConfig.serverUrl.value.isBlank()) return Result.success()
        // The fares are fetched on the same schedule as the upload. A failed fetch changes nothing and does not
        // fail the run: the saved fares stay in use, and the next run tries again. If the work itself is cancelled
        // that is not a failure to absorb: refreshSafely rethrows it, so the run stops instead of carrying on.
        configRefresher.refreshSafely()
        val report = flusher.flush()
        return if (report.failure == null) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "outbox-upload"

        /** Connectivity both drains need: the periodic backstop and the one-time drain a fresh event asks for. */
        internal val NETWORK_CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(NETWORK_CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

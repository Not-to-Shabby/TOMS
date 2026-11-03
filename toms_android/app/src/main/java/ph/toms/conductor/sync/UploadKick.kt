package ph.toms.conductor.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Requests an outbox drain right away, fired by [ph.toms.conductor.data.TripRepository] once an event
 * is written and committed. Without it the events a passenger's receipt depends on wait for the
 * periodic drain, so a card scanned mid-trip could read as having no active trip.
 *
 * Implementations must tolerate being called for every event: while a drain is already queued or
 * running, further requests are dropped, because one drain empties the whole outbox anyway.
 */
interface UploadKick {
    fun kick()

    /** Stands in where uploads are not wired up, so writers never need a null check. */
    object None : UploadKick {
        override fun kick() {}
    }
}

/**
 * Drains the outbox through [UploadWorker] as a unique one-time job: it survives the app being killed,
 * waits for connectivity when the phone is offline, and the 15-minute periodic drain stays as the backstop.
 */
@Singleton
class WorkManagerUploadKick @Inject constructor(
    @ApplicationContext private val context: Context,
) : UploadKick {
    override fun kick() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(UploadWorker.NETWORK_CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    companion object {
        const val UNIQUE_NAME = "outbox-upload-now"
    }
}

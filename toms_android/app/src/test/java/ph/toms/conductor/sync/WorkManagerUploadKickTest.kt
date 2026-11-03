package ph.toms.conductor.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkManagerUploadKickTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `repeated kicks coalesce into one pending drain`() {
        val kick = WorkManagerUploadKick(context)
        kick.kick()
        kick.kick()
        kick.kick()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerUploadKick.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }

    @Test
    fun `the kicked drain waits for connectivity like the periodic one`() {
        WorkManagerUploadKick(context).kick()

        val info = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerUploadKick.UNIQUE_NAME).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}

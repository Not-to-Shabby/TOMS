package ph.toms.conductor.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import ph.toms.conductor.ble.FakeReturnMachineLink
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.sync.FakeUplinkManager
import ph.toms.conductor.sync.UploadStats

/** Records the shift boundaries instead of writing telemetry. Used by view model tests. */
class FakeDeviceStatusReporter : DeviceStatusReporter {
    var starts = 0
    var stops = 0
    val startedFor = mutableListOf<String>()
    val stoppedFor = mutableListOf<String>()

    override fun start(scope: CoroutineScope, openShiftId: String, fixProvider: () -> GeoFix?) {
        starts++
        startedFor.add(openShiftId)
    }

    override suspend fun stop(endedShiftId: String) {
        stops++
        stoppedFor.add(endedShiftId)
    }

    fun reset() {
        starts = 0
        stops = 0
        startedFor.clear()
        stoppedFor.clear()
    }
}

/** Builds a real reporter over the given in-memory database for tests that assert the written payload. */
fun testDeviceStatusReporter(
    repo: ph.toms.conductor.data.TripRepository,
    db: ph.toms.conductor.data.db.TomsDatabase,
    uplink: ph.toms.conductor.sync.UplinkManager = FakeUplinkManager(),
    rmLink: ph.toms.conductor.ble.ReturnMachineLink = FakeReturnMachineLink(),
    stats: UploadStats = UploadStats(),
    context: Context = ApplicationProvider.getApplicationContext(),
) = RealDeviceStatusReporter(
    trips = repo, outbox = db.outbox(), events = db.events(),
    uplink = uplink, rmLink = rmLink, stats = stats, context = context,
)


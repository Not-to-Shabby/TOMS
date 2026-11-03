package ph.toms.conductor.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class AndroidUplinkManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : UplinkManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val coordinator = UplinkCoordinator(scope)

    override val state: StateFlow<UplinkState> = coordinator.state
    override val uplinkRestored: SharedFlow<Unit> = coordinator.uplinkRestored

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (hasInternet && isValidated) {
                coordinator.onNetworkAvailable(isCellular = isCellular, isValidated = true)
            } else if (hasInternet) {
                coordinator.onNetworkAvailable(isCellular = isCellular, isValidated = false)
            } else {
                coordinator.onNetworkLost()
            }
        }

        override fun onLost(network: Network) {
            // Check if there is another active network before declaring offline
            val activeCaps = connectivityManager?.activeNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
            }
            if (activeCaps != null && activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                val isCellular = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val isValidated = activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                coordinator.onNetworkAvailable(isCellular, isValidated)
            } else {
                coordinator.onNetworkLost()
            }
        }

        override fun onUnavailable() {
            coordinator.onNetworkLost()
        }
    }

    override fun startMonitoring() {
        val cm = connectivityManager ?: return
        if (registered) return

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            registered = true

            // Initialize with current active network capability if already available
            cm.activeNetwork?.let { net ->
                cm.getNetworkCapabilities(net)?.let { caps ->
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    coordinator.onNetworkAvailable(isCellular, isValidated)
                }
            }
        } catch (_: Exception) {
            // In restricted test environments or sandbox, catch and remain in coordinator's default state
        }
    }

    override fun stopMonitoring() {
        if (!registered) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        } finally {
            registered = false
        }
    }
}

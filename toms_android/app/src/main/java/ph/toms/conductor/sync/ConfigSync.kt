package ph.toms.conductor.sync

import javax.inject.Inject
import javax.inject.Singleton
import ph.toms.conductor.data.DeviceConfigResponse
import ph.toms.conductor.data.InvalidServerConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.domain.ServerConfig

/** What asking the server for this phone's fares came back with. */
sealed interface ConfigFetch {
    data class Loaded(val response: DeviceConfigResponse) : ConfigFetch

    /** The server answered, and has no fares for this phone yet (no vehicle, route, stops or published fare). */
    data class Unavailable(val reason: String) : ConfigFetch

    /** No answer, or one that could not be used: no signal, a refused token, a server error. */
    data class Failed(val reason: String) : ConfigFetch
}

interface ConfigSync {
    suspend fun fetchConfig(): ConfigFetch
}

sealed interface ConfigRefresh {
    /** A new copy was checked and saved. */
    data class Updated(val config: ServerConfig) : ConfigRefresh

    /** Nothing was saved. Whatever copy the phone already had is still the one in use. */
    data class Kept(val reason: String) : ConfigRefresh
}

/**
 * Downloads the config and saves it. It only ever replaces the saved copy with a complete, checked one: a failed
 * fetch, a "nothing published" answer and a response that fails its checks all leave the saved copy alone, so the
 * phone keeps working offline with the last good fares.
 */
@Singleton
class ConfigRefresher @Inject constructor(
    private val trips: TripRepository,
    private val sync: ConfigSync,
) {
    suspend fun refresh(): ConfigRefresh = when (val fetched = sync.fetchConfig()) {
        is ConfigFetch.Loaded ->
            try {
                ConfigRefresh.Updated(trips.saveServerConfig(fetched.response))
            } catch (e: InvalidServerConfig) {
                ConfigRefresh.Kept("The server sent fares this phone cannot use (${e.message}). Tell the office.")
            }
        is ConfigFetch.Unavailable -> ConfigRefresh.Kept(fetched.reason)
        is ConfigFetch.Failed -> ConfigRefresh.Kept(fetched.reason)
    }
}

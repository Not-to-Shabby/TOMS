package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.db.ServerConfigEntity
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.FareCalculator
import ph.toms.conductor.domain.MAX_FARE_VERSION
import ph.toms.conductor.domain.TomsConfig
import ph.toms.conductor.sync.ConfigFetch
import ph.toms.conductor.sync.ConfigRefresh
import ph.toms.conductor.sync.ConfigRefresher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerConfigTest {

    private lateinit var db: TomsDatabase
    private lateinit var repo: TripRepository
    private val sync = FakeConfigSync()
    private lateinit var refresher: ConfigRefresher
    private var now = 1_000_000L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db, "dev-1", clock = { now })
        refresher = ConfigRefresher(repo, sync)
    }

    @After fun tearDown() = db.close()

    // ---- parsing what the server sends

    @Test fun `the real response shape parses, centavos and all`() {
        val body = """
            {"version":4242,"route":{"id":7,"name":"Tibanga - Pala-o"},
             "stops":[{"id":"11","name":"Tibanga","lat":8.228,"lon":124.245,"radius_m":120},
                      {"id":"12","name":"Pala-o","lat":8.273,"lon":124.245,"radius_m":100}],
             "fare":{"matrix_id":9,"base_fare_centavos":1800,"base_distance_km":4,"per_km_fare_centavos":300,
                     "rounding_step_cents":100,
                     "discounts":[{"id":"pwd","name":"PWD","percent":20},{"id":"student","name":"Student","percent":15}]}}
        """.trimIndent()
        val config = Json { ignoreUnknownKeys = true }.decodeFromString<DeviceConfigResponse>(body).toServerConfig(55L)

        assertEquals(4242, config.version)
        assertEquals("Tibanga - Pala-o", config.routeName)
        assertEquals(listOf("11", "12"), config.stops.map { it.id })
        assertEquals(1800, config.fare.baseFareCentavos)
        assertEquals(4.0, config.fare.baseDistanceKm, 0.0)
        assertEquals(300, config.fare.perKmCentavos)
        assertEquals(100, config.fare.roundingStepCentavos)
        assertEquals(listOf("pwd" to 20, "student" to 15), config.discountCategories.map { it.id to it.percent })
        assertEquals(55L, config.fetchedAtMillis)
    }

    @Test fun `the fare version is the server's, on the config and on the fare`() {
        val config = serverConfigResponse(version = 777).toServerConfig(0)
        assertEquals(777, config.version)
        assertEquals(777, config.fare.version)
    }

    @Test fun `a fare worked out from the downloaded numbers uses the server's version and rounding`() {
        val config = serverConfigResponse(version = 321, baseFareCentavos = 1800, perKmFareCentavos = 300).toServerConfig(0)
        val quote = FareCalculator.quote(2_000.0, null, config.fare)
        assertEquals(1800, quote.fareCentavos)
        assertEquals(321, quote.fareVersion)
        val farther = FareCalculator.quote(6_500.0, null, config.fare)
        assertEquals(1800 + 3 * 300, farther.fareCentavos)
    }

    @Test fun `a config the phone cannot charge from is refused whole`() {
        fun refused(name: String, response: DeviceConfigResponse) {
            try {
                response.toServerConfig(0)
                fail("$name should have been refused")
            } catch (_: InvalidServerConfig) {
            }
        }
        refused("version 0", serverConfigResponse(version = 0))
        refused("version over the server limit", serverConfigResponse(version = MAX_FARE_VERSION + 1))
        refused("one stop", serverConfigResponse(stops = listOf(StopDto("1", "Only", 8.0, 124.0))))
        refused("no stops", serverConfigResponse(stops = emptyList()))
        refused("repeated stop id", serverConfigResponse(stops = listOf(StopDto("1", "A", 8.0, 124.0), StopDto("1", "B", 8.1, 124.0))))
        refused("a stop with no name", serverConfigResponse(stops = listOf(StopDto("1", " ", 8.0, 124.0), StopDto("2", "B", 8.1, 124.0))))
        refused("latitude off the map", serverConfigResponse(stops = listOf(StopDto("1", "A", 91.0, 124.0), StopDto("2", "B", 8.1, 124.0))))
        refused("longitude not a number", serverConfigResponse(stops = listOf(StopDto("1", "A", 8.0, Double.NaN), StopDto("2", "B", 8.1, 124.0))))
        refused("negative base fare", serverConfigResponse(baseFareCentavos = -1))
        refused("absurd base fare", serverConfigResponse(baseFareCentavos = Int.MAX_VALUE))
        refused("negative per-km", serverConfigResponse(perKmFareCentavos = -5))
        refused("zero base distance", serverConfigResponse(baseDistanceKm = 0.0))
        refused("zero rounding step", serverConfigResponse(roundingStepCents = 0))
        refused("discount over 100", serverConfigResponse(discounts = listOf(DiscountDto("student", "Student", 101))))
        refused("negative discount", serverConfigResponse(discounts = listOf(DiscountDto("student", "Student", -1))))
        refused("repeated discount id", serverConfigResponse(discounts = listOf(DiscountDto("pwd", "A", 10), DiscountDto("pwd", "B", 10))))
    }

    @Test fun `a response with a field missing does not decode, so it is never half-used`() {
        val json = Json { ignoreUnknownKeys = true }
        try {
            json.decodeFromString<DeviceConfigResponse>("""{"version":1,"route":{"id":1,"name":"R"},"stops":[]}""")
            fail("a response with no fare should not decode")
        } catch (_: kotlinx.serialization.SerializationException) {
        }
    }

    // ---- saving, and reading it back

    @Test fun `a saved config comes back unchanged after a restart`() = runBlocking {
        val saved = repo.saveServerConfig(serverConfigResponse(version = 99))
        val restarted = TripRepository(db, "dev-1", clock = { now })
        val loaded = restarted.loadServerConfig()

        assertNotNull(loaded)
        assertEquals(saved, loaded)
        assertEquals(99, loaded!!.version)
        assertEquals(now, loaded.fetchedAtMillis)
        assertEquals(3, loaded.stops.size)
    }

    @Test fun `with nothing saved there is no config, not a default one`() = runBlocking {
        assertNull(repo.loadServerConfig())
        assertNull(repo.observeServerConfig().first())
    }

    @Test fun `a new config replaces the saved one, there is only ever one`() = runBlocking {
        repo.saveServerConfig(serverConfigResponse(version = 1, baseFareCentavos = 1500))
        now += 5_000
        repo.saveServerConfig(serverConfigResponse(version = 2, baseFareCentavos = 2000))

        val loaded = repo.loadServerConfig()!!
        assertEquals(2, loaded.version)
        assertEquals(2000, loaded.fare.baseFareCentavos)
        assertEquals(now, loaded.fetchedAtMillis)
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM server_config").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun `a config that fails its checks is not saved and the old one stays`() = runBlocking {
        repo.saveServerConfig(serverConfigResponse(version = 1))
        try {
            repo.saveServerConfig(serverConfigResponse(version = 2, baseFareCentavos = -1))
            fail("should have been refused")
        } catch (_: InvalidServerConfig) {
        }
        assertEquals(1, repo.loadServerConfig()!!.version)
    }

    @Test fun `a damaged saved copy reads as none instead of crashing`() = runBlocking {
        db.serverConfig().put(ServerConfigEntity(version = 5, routeName = "R", json = "{not json", fetchedAtMillis = 1))
        assertNull(repo.loadServerConfig())
        db.serverConfig().put(ServerConfigEntity(version = 5, routeName = "R", json = """{"version":0}""", fetchedAtMillis = 1))
        assertNull(repo.loadServerConfig())
    }

    @Test fun `the config applied to the phone keeps the approved cards`() {
        val cards = TestConfig.config.approvedCards
        val applied = serverConfigResponse(version = 8).toServerConfig(1).applyTo(TestConfig.config)
        assertEquals(8, applied.version)
        assertEquals(8, applied.fare.version)
        assertEquals(3, applied.stops.size)
        assertEquals(cards, applied.approvedCards)
        assertNull("the test stops are gone", applied.stop("s1"))
        assertNotNull(applied.stop("11"))
    }

    @Test fun `the empty config prices nothing`() {
        val empty = TomsConfig.EMPTY
        assertTrue(empty.stops.isEmpty())
        assertTrue(empty.discountCategories.isEmpty())
        assertEquals(0, empty.approvedCards.size)
    }

    // ---- fetching, offline, and "nothing published"

    @Test fun `a fetch that works is checked, saved and reported`() = runBlocking {
        sync.next = ConfigFetch.Loaded(serverConfigResponse(version = 31))
        val result = refresher.refresh()

        assertTrue(result is ConfigRefresh.Updated)
        assertEquals(31, repo.loadServerConfig()!!.version)
    }

    @Test fun `offline keeps the saved config`() = runBlocking {
        repo.saveServerConfig(serverConfigResponse(version = 10, baseFareCentavos = 1700))
        sync.next = ConfigFetch.Failed("no signal")

        val result = refresher.refresh()

        assertTrue(result is ConfigRefresh.Kept)
        assertEquals("no signal", (result as ConfigRefresh.Kept).reason)
        assertEquals(10, repo.loadServerConfig()!!.version)
        assertEquals(1700, repo.loadServerConfig()!!.fare.baseFareCentavos)
    }

    @Test fun `a 404 from the server keeps the saved config and says why`() = runBlocking {
        repo.saveServerConfig(serverConfigResponse(version = 10))
        sync.next = ConfigFetch.Unavailable("No active fare matrix. Publish one on the Fare & LTFRB page.")

        val result = refresher.refresh() as ConfigRefresh.Kept

        assertEquals("No active fare matrix. Publish one on the Fare & LTFRB page.", result.reason)
        assertEquals(10, repo.loadServerConfig()!!.version)
    }

    @Test fun `a bad response keeps the saved config and says the fares cannot be used`() = runBlocking {
        repo.saveServerConfig(serverConfigResponse(version = 10))
        sync.next = ConfigFetch.Loaded(serverConfigResponse(version = 11, discounts = listOf(DiscountDto("student", "Student", 150))))

        val result = refresher.refresh() as ConfigRefresh.Kept

        assertTrue(result.reason, result.reason.contains("cannot use"))
        assertEquals(10, repo.loadServerConfig()!!.version)
    }

    @Test fun `with no saved config, a failed fetch leaves none`() = runBlocking {
        sync.next = ConfigFetch.Unavailable("The assigned route needs at least two stops.")
        refresher.refresh()
        assertNull(repo.loadServerConfig())
    }

    @Test fun `observing follows a save made later, as the background worker makes one`() = runBlocking {
        assertNull(repo.observeServerConfig().first())
        sync.next = ConfigFetch.Loaded(serverConfigResponse(version = 64))
        refresher.refresh()
        assertEquals(64, repo.observeServerConfig().first()!!.version)
    }
}

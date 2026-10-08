package ph.toms.conductor.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.settings.DeviceConfigStore

class RetrofitEventUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    private class TestConfigStore(
        override val deviceId: String = "dev-test-1",
        initialUrl: String = "http://localhost:8080",
        initialToken: String? = "dev-test-1.secret123",
    ) : DeviceConfigStore {
        override val serverUrl: StateFlow<String> = MutableStateFlow(initialUrl)
        override val deviceToken: StateFlow<String?> = MutableStateFlow(initialToken)
        override fun setServerUrl(url: String) {}
        override fun setDeviceToken(token: String?) {}
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun pendingEvent(id: String = "e1", seq: Long = 1) = PendingEvent(
        seq = seq,
        eventId = id,
        deviceId = "dev-test-1",
        type = "trip_created",
        payload = """{"fareCentavos":1500}""",
        createdAtMillis = 10_000L,
        attemptCount = 0,
    )

    @Test
    fun `empty batch completes immediately with empty ack without hitting network`() = runBlocking {
        val config = TestConfigStore(initialUrl = server.url("/").toString())
        val uploader = RetrofitEventUploader(config, okHttpClient)
        val res = uploader.upload(emptyList())
        assertTrue(res is UploadResult.Acked)
        assertEquals(emptySet<String>(), (res as UploadResult.Acked).eventIds)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `upload fails if device token is missing`() = runBlocking {
        val config = TestConfigStore(initialUrl = server.url("/").toString(), initialToken = null)
        val uploader = RetrofitEventUploader(config, okHttpClient)
        val res = uploader.upload(listOf(pendingEvent()))
        assertTrue(res is UploadResult.Failed)
        assertTrue((res as UploadResult.Failed).reason.contains("not enrolled"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful upload acks accepted and duplicate event ids`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"accepted":["e1"],"duplicates":["e2"],"rejected":[]}"""),
        )
        val config = TestConfigStore(initialUrl = server.url("/").toString())
        val uploader = RetrofitEventUploader(config, okHttpClient)
        val res = uploader.upload(listOf(pendingEvent("e1", 1), pendingEvent("e2", 2)))

        assertTrue(res is UploadResult.Acked)
        val acked = (res as UploadResult.Acked).eventIds
        assertEquals(setOf("e1", "e2"), acked)

        val recorded = server.takeRequest()
        assertEquals("/api/events", recorded.path)
        assertEquals("Bearer dev-test-1.secret123", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"event_id\":\"e1\""))
        assertTrue(body.contains("\"delivery_channel\":\"data_a\""))
    }

    @Test
    fun `HTTP 401 returns failed with status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val config = TestConfigStore(initialUrl = server.url("/").toString())
        val uploader = RetrofitEventUploader(config, okHttpClient)
        val res = uploader.upload(listOf(pendingEvent()))

        assertTrue(res is UploadResult.Failed)
        assertTrue((res as UploadResult.Failed).reason.contains("401"))
    }

    @Test
    fun `server down or connection error returns failed`() = runBlocking {
        // Shutdown server immediately to cause connection error
        server.shutdown()
        val config = TestConfigStore(initialUrl = "http://127.0.0.1:${server.port}")
        val uploader = RetrofitEventUploader(config, okHttpClient)
        val res = uploader.upload(listOf(pendingEvent()))

        assertTrue(res is UploadResult.Failed)
    }
}

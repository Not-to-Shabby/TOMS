package ph.toms.conductor.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.settings.DeviceConfigStore
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class EventUploadItem(
    @SerialName("event_id") val eventId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("local_seq") val localSeq: Long,
    val type: String,
    @SerialName("created_at_millis") val createdAtMillis: Long,
    val payload: JsonElement,
)

@Serializable
data class EventUploadEnvelope(
    val events: List<EventUploadItem>,
    @SerialName("delivery_channel") val deliveryChannel: String = "data_a",
)

@Serializable
data class IngestResponse(
    val accepted: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val rejected: List<IngestRejection> = emptyList(),
)

@Serializable
data class IngestRejection(
    val index: Int,
    @SerialName("event_id") val eventId: String? = null,
    val reason: String = "",
)

@Serializable
data class ApprovedCardsResponse(
    val cards: Map<String, String> = emptyMap(),
    val count: Int = 0,
    val timestamp: String = "",
)

interface TomsApiService {
    @POST("api/events")
    suspend fun uploadEvents(
        @Header("Authorization") authHeader: String?,
        @Body envelope: EventUploadEnvelope,
    ): IngestResponse

    @GET("api/cards/approved")
    suspend fun fetchApprovedCards(
        @Header("Authorization") authHeader: String?,
    ): ApprovedCardsResponse
}

interface CardRegistrySync {
    suspend fun fetchApprovedCards(): Result<Map<String, String>>
}

@Singleton
class RetrofitEventUploader @Inject constructor(
    private val config: DeviceConfigStore,
    private val okHttpClient: OkHttpClient,
) : EventUploader, CardRegistrySync {

    override val channel: String = "data_a"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val contentType = "application/json".toMediaType()

    private fun createApi(): TomsApiService? {
        val baseUrl = config.serverUrl.value.trimEnd('/') + "/"
        return try {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(TomsApiService::class.java)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun upload(batch: List<PendingEvent>): UploadResult {
        if (batch.isEmpty()) return UploadResult.Acked(emptySet())
        val token = config.deviceToken.value
        if (token.isNullOrBlank()) {
            return UploadResult.Failed("device not enrolled (no token configured)")
        }

        val api = createApi() ?: return UploadResult.Failed("invalid server URL")

        val items = batch.map { p ->
            val payloadElement = runCatching { json.parseToJsonElement(p.payload) }
                .getOrDefault(json.parseToJsonElement("{}"))
            EventUploadItem(
                eventId = p.eventId,
                deviceId = p.deviceId,
                localSeq = p.seq,
                type = p.type,
                createdAtMillis = p.createdAtMillis,
                payload = payloadElement,
            )
        }

        return try {
            val res = api.uploadEvents("Bearer $token", EventUploadEnvelope(items, channel))
            val acked = (res.accepted + res.duplicates).toSet()
            UploadResult.Acked(acked)
        } catch (e: HttpException) {
            UploadResult.Failed("HTTP ${e.code()}: ${e.message()}")
        } catch (e: Exception) {
            UploadResult.Failed(e.message ?: "network error")
        }
    }

    override suspend fun fetchApprovedCards(): Result<Map<String, String>> {
        val token = config.deviceToken.value
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("device not enrolled (no token configured)"))
        }
        val api = createApi() ?: return Result.failure(IllegalStateException("invalid server URL"))
        return try {
            val res = api.fetchApprovedCards("Bearer $token")
            Result.success(res.cards)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

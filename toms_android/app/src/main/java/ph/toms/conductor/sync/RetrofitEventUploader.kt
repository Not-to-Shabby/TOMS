package ph.toms.conductor.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import ph.toms.conductor.data.DeviceConfigResponse
import ph.toms.conductor.data.db.PendingEvent
import ph.toms.conductor.domain.ApprovedCardList
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
    /** Chips of cards that exist but must not be used, with why. Missing from an older server, so it defaults to none. */
    val statuses: Map<String, String> = emptyMap(),
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

    @GET("api/device/config")
    suspend fun fetchConfig(
        @Header("Authorization") authHeader: String?,
    ): DeviceConfigResponse
}

interface CardRegistrySync {
    suspend fun fetchApprovedCards(): Result<ApprovedCardList>
}

@Singleton
class RetrofitEventUploader @Inject constructor(
    private val config: DeviceConfigStore,
    private val okHttpClient: OkHttpClient,
) : EventUploader, CardRegistrySync, ConfigSync {

    override val channel: String = "data_a"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val contentType = "application/json".toMediaType()

    /** The service for the last server URL used. Rebuilt only when the URL changes, not on every call. */
    private var cachedApi: Pair<String, TomsApiService>? = null

    @Synchronized
    private fun createApi(): TomsApiService? {
        val baseUrl = config.serverUrl.value.trimEnd('/') + "/"
        cachedApi?.let { (url, api) -> if (url == baseUrl) return api }
        return try {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(TomsApiService::class.java)
                .also { cachedApi = baseUrl to it }
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
            // A rejection names the event by ID, or only by its index in the batch.
            val rejected = res.rejected.mapNotNull { r ->
                val id = r.eventId ?: batch.getOrNull(r.index)?.eventId
                id?.let { it to r.reason.ifBlank { "rejected by server" } }
            }.toMap()
            UploadResult.Acked(acked, rejected)
        } catch (e: HttpException) {
            UploadResult.Failed("HTTP ${e.code()}: ${e.message()}")
        } catch (e: Exception) {
            UploadResult.Failed(e.message ?: "network error")
        }
    }

    override suspend fun fetchConfig(): ConfigFetch {
        val token = config.deviceToken.value
        if (token.isNullOrBlank()) return ConfigFetch.Failed("Not enrolled. Set the device token on More.")
        val api = createApi() ?: return ConfigFetch.Failed("The server URL is not valid.")
        return try {
            ConfigFetch.Loaded(api.fetchConfig("Bearer $token"))
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> ConfigFetch.Unavailable(serverReason(e) ?: "The server has no fares for this phone yet.")
                401 -> ConfigFetch.Failed("The server did not accept this phone's token (401).")
                else -> ConfigFetch.Failed("The server answered ${e.code()}.")
            }
        } catch (e: Exception) {
            ConfigFetch.Failed(e.message ?: "No connection to the server.")
        }
    }

    /** The server's own explanation, the `error` text of an error answer, when it sent one. */
    private fun serverReason(e: HttpException): String? = runCatching {
        val body = e.response()?.errorBody()?.string() ?: return null
        (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    override suspend fun fetchApprovedCards(): Result<ApprovedCardList> {
        val token = config.deviceToken.value
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("device not enrolled (no token configured)"))
        }
        val api = createApi() ?: return Result.failure(IllegalStateException("invalid server URL"))
        return try {
            val res = api.fetchApprovedCards("Bearer $token")
            Result.success(ApprovedCardList(res.cards, res.statuses))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package ph.toms.conductor.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.sync.ConductorAuthService

data class Vehicle(val id: String, val plate: String)
data class RouteInfo(val id: String, val name: String)

data class Assignment(val conductor: String, val vehicle: Vehicle, val route: RouteInfo)

interface SessionRepository {
    suspend fun login(username: String, password: String): Result<String>
    suspend fun vehicles(): List<Vehicle>
    suspend fun routes(): List<RouteInfo>

    /** The conductor signed in before the app was closed, or null when there is none or the sign-in has expired. */
    suspend fun restore(nowMillis: Long): String? = null

    /** Ends the sign-in only. An open shift is not touched. */
    fun signOut() {}
}

/**
 * Communicates with POST /api/conductors/login on the server to verify real conductor credentials, and keeps the
 * returned token (encrypted, see [ConductorSessionStore]) so the sign-in survives a restart until the token expires.
 * When in test mode (BuildConfig.TEST_FARES), falls back to [stubRepo] if offline or no server is set.
 */
@Singleton
class ServerSessionRepository @Inject constructor(
    private val authService: ConductorAuthService,
    private val stubRepo: StubSessionRepository,
    private val store: ConductorSessionStore,
) : SessionRepository {

    override suspend fun login(username: String, password: String): Result<String> {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isBlank() || cleanPass.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter username and password"))
        }

        val serverResult = authService.login(cleanUser, cleanPass)
        if (serverResult.isFailure) {
            // Test builds without a server fall back to the stub; that sign-in has no token, so nothing is kept.
            return if (BuildConfig.TEST_FARES) stubRepo.login(cleanUser, cleanPass) else Result.failure(serverResult.exceptionOrNull()!!)
        }
        val login = serverResult.getOrThrow()
        // Only the token is kept, and only when its expiry can be read; the password is dropped here.
        JwtExpiry.millis(login.token)?.let { store.save(StoredSession(login.displayName, login.token, it)) }
        return Result.success(login.displayName)
    }

    override suspend fun restore(nowMillis: Long): String? {
        val saved = store.load() ?: return null
        if (saved.expiresAtMillis <= nowMillis) {
            store.clear()
            return null
        }
        return saved.name
    }

    override fun signOut() = store.clear()

    override suspend fun vehicles(): List<Vehicle> = stubRepo.vehicles()
    override suspend fun routes(): List<RouteInfo> = stubRepo.routes()
}

@Singleton
class StubSessionRepository @Inject constructor() : SessionRepository {
    override suspend fun login(username: String, password: String): Result<String> {
        delay(200)
        return if (username.isBlank() || password.isBlank()) {
            Result.failure(IllegalArgumentException("Enter username and password"))
        } else {
            Result.success(username.trim())
        }
    }

    override suspend fun vehicles() = listOf(Vehicle("v1", "TOMS-001"), Vehicle("v2", "TOMS-002"))

    override suspend fun routes() = listOf(
        RouteInfo("r1", "Tibanga - Pala-o"),
        RouteInfo("r2", "Tubod - City Proper"),
    )
}

package ph.toms.conductor.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import ph.toms.conductor.BuildConfig
import ph.toms.conductor.sync.ConductorAuthService

data class Vehicle(val id: String, val plate: String)
data class RouteInfo(val id: String, val name: String)

data class Assignment(val conductor: String, val vehicle: Vehicle, val route: RouteInfo)

/** Stand-in for the real backend until Phase 1.4. Accepts any non-blank credentials. */
interface SessionRepository {
    suspend fun login(username: String, password: String): Result<String>
    suspend fun vehicles(): List<Vehicle>
    suspend fun routes(): List<RouteInfo>
}

/**
 * Communicates with POST /api/conductors/login on the server to verify real conductor credentials.
 * When in test mode (BuildConfig.TEST_FARES), falls back to [stubRepo] if offline or no server is set.
 */
@Singleton
class ServerSessionRepository @Inject constructor(
    private val authService: ConductorAuthService,
    private val stubRepo: StubSessionRepository,
) : SessionRepository {

    override suspend fun login(username: String, password: String): Result<String> {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isBlank() || cleanPass.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter username and password"))
        }

        if (BuildConfig.TEST_FARES) {
            val serverResult = authService.login(cleanUser, cleanPass)
            if (serverResult.isSuccess) return serverResult
            return stubRepo.login(cleanUser, cleanPass)
        }

        return authService.login(cleanUser, cleanPass)
    }

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

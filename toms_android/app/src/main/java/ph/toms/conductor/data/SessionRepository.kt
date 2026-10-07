package ph.toms.conductor.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

data class Vehicle(val id: String, val plate: String)
data class RouteInfo(val id: String, val name: String)

data class Assignment(val conductor: String, val vehicle: Vehicle, val route: RouteInfo)

/** Stand-in for the real backend until Phase 1.4. Accepts any non-blank credentials. */
interface SessionRepository {
    suspend fun login(username: String, password: String): Result<String>
    suspend fun vehicles(): List<Vehicle>
    suspend fun routes(): List<RouteInfo>
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

package ph.toms.conductor.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.toms.conductor.sync.ConductorAuthService

class ServerSessionRepositoryTest {

    private class FakeAuthService(var result: Result<String>) : ConductorAuthService {
        var loginCalls = 0
        override suspend fun login(username: String, password: String): Result<String> {
            loginCalls++
            return result
        }
    }

    private val stub = StubSessionRepository()

    @Test
    fun `blank username or password fails immediately without hitting auth service`() = runBlocking {
        val auth = FakeAuthService(Result.success("Juan"))
        val repo = ServerSessionRepository(auth, stub)

        val r1 = repo.login("", "secret")
        assertTrue(r1.isFailure)
        assertEquals("Enter username and password", r1.exceptionOrNull()?.message)

        val r2 = repo.login("juan", "   ")
        assertTrue(r2.isFailure)
        assertEquals("Enter username and password", r2.exceptionOrNull()?.message)

        assertEquals(0, auth.loginCalls)
    }

    @Test
    fun `successful server login returns conductor name`() = runBlocking {
        val auth = FakeAuthService(Result.success("Juan Dela Cruz"))
        val repo = ServerSessionRepository(auth, stub)

        val res = repo.login("juan", "correctpass")
        assertTrue(res.isSuccess)
        assertEquals("Juan Dela Cruz", res.getOrThrow())
        assertEquals(1, auth.loginCalls)
    }

    @Test
    fun `server authentication error is propagated to caller`() = runBlocking {
        val auth = FakeAuthService(Result.failure(Exception("Invalid username or password")))
        val repo = ServerSessionRepository(auth, stub)

        val res = repo.login("juan", "wrongpass")
        assertTrue(res.isFailure)
        assertEquals("Invalid username or password", res.exceptionOrNull()?.message)
        assertEquals(1, auth.loginCalls)
    }
}

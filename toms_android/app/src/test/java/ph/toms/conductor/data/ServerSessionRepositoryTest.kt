package ph.toms.conductor.data

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.toms.conductor.sync.ConductorAuthService
import ph.toms.conductor.sync.ConductorLogin

/** A token shaped like the server's: header.payload.signature, with only `exp` filled in. */
fun fakeJwt(expSeconds: Long): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val payload = enc.encodeToString("""{"role":"conductor","exp":$expSeconds}""".toByteArray())
    return "e30.$payload.sig"
}

class MemorySessionStore : ConductorSessionStore {
    var saved: StoredSession? = null
    override fun load() = saved
    override fun save(session: StoredSession): Boolean { saved = session; return true }
    override fun clear() { saved = null }
}

class ServerSessionRepositoryTest {

    private class FakeAuthService(var result: Result<ConductorLogin>) : ConductorAuthService {
        var loginCalls = 0
        override suspend fun login(username: String, password: String): Result<ConductorLogin> {
            loginCalls++
            return result
        }
    }

    private val stub = StubSessionRepository()
    private val store = MemorySessionStore()
    private val now = 1_000_000_000_000L

    private fun login(name: String, expSeconds: Long) = Result.success(ConductorLogin(name, fakeJwt(expSeconds)))

    @Test
    fun `blank username or password fails immediately without hitting auth service`() = runBlocking {
        val auth = FakeAuthService(login("Juan", now / 1000 + 100))
        val repo = ServerSessionRepository(auth, stub, store)

        val r1 = repo.login("", "secret")
        assertTrue(r1.isFailure)
        assertEquals("Enter username and password", r1.exceptionOrNull()?.message)

        val r2 = repo.login("juan", "   ")
        assertTrue(r2.isFailure)
        assertEquals("Enter username and password", r2.exceptionOrNull()?.message)

        assertEquals(0, auth.loginCalls)
        assertNull(store.saved)
    }

    @Test
    fun `successful server login returns conductor name and keeps only the token`() = runBlocking {
        val auth = FakeAuthService(login("Juan Dela Cruz", now / 1000 + 3600))
        val repo = ServerSessionRepository(auth, stub, store)

        val res = repo.login("juan", "correctpass")
        assertTrue(res.isSuccess)
        assertEquals("Juan Dela Cruz", res.getOrThrow())
        assertEquals(1, auth.loginCalls)
        val saved = store.saved!!
        assertEquals("Juan Dela Cruz", saved.name)
        assertEquals((now / 1000 + 3600) * 1000, saved.expiresAtMillis)
        assertTrue("the password must not be stored", !saved.toString().contains("correctpass"))
    }

    @Test
    fun `wrong password is propagated and nothing is saved`() = runBlocking {
        val auth = FakeAuthService(Result.failure(Exception("Invalid username or password")))
        val repo = ServerSessionRepository(auth, stub, store)

        val res = repo.login("juan", "wrongpass")
        assertTrue(res.isFailure)
        assertEquals("Invalid username or password", res.exceptionOrNull()?.message)
        assertEquals(1, auth.loginCalls)
        assertNull(store.saved)
        assertNull(repo.restore(now))
    }

    @Test
    fun `a saved sign-in comes back after a restart while the token is valid`() = runBlocking {
        ServerSessionRepository(FakeAuthService(login("Juan", now / 1000 + 3600)), stub, store).login("juan", "pw")

        val afterRestart = ServerSessionRepository(FakeAuthService(Result.failure(Exception("offline"))), stub, store)
        assertEquals("Juan", afterRestart.restore(now))
    }

    @Test
    fun `an expired token is not restored and is deleted`() = runBlocking {
        val repo = ServerSessionRepository(FakeAuthService(login("Juan", now / 1000 + 60)), stub, store)
        repo.login("juan", "pw")

        assertNull(repo.restore(now + 61_000))
        assertNull(store.saved)
    }

    @Test
    fun `sign out forgets the sign-in`() = runBlocking {
        val repo = ServerSessionRepository(FakeAuthService(login("Juan", now / 1000 + 3600)), stub, store)
        repo.login("juan", "pw")

        repo.signOut()

        assertNull(repo.restore(now))
    }

    @Test
    fun `a token whose expiry cannot be read is not kept`() = runBlocking {
        val repo = ServerSessionRepository(FakeAuthService(Result.success(ConductorLogin("Juan", "not-a-jwt"))), stub, store)

        assertTrue(repo.login("juan", "pw").isSuccess)
        assertNull(store.saved)
    }
}
